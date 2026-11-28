package com.mytv.mymusic.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.sync.readChunkedText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 同步基线是整份同步文件 JSON，歌单多时会超过 CursorWindow 约 2 MB 的上限。
 * 直接 SELECT * 读整行会抛 "Row too big to fit into CursorWindow"，
 * 必须按字符分块取回再拼接；这里守住分块读取能完整还原。
 *
 * 读回统一走生产代码的 [readChunkedText]，不再在用例里另抄一遍循环——
 * 抄一份就测不到真正的 offset 推进逻辑。
 */
@RunWith(AndroidJUnit4::class)
class SyncBaseChunkTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SyncDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.sync()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun chunkedReadRebuildsLargeBase() = runBlocking {
        // 80 万个汉字约 2.4 MB（UTF-8），确保超过 CursorWindow 的默认上限
        val snapshot = buildString {
            append("{\"schema\":3,\"playlists\":[")
            repeat(800_000) { append('歌') }
            append("]}")
        }
        dao.putBase(
            SyncBaseEntity(
                accountId = "webdav",
                snapshot = snapshot,
                remoteRevision = "r1",
                updatedAt = 0L,
            )
        )

        assertEquals(snapshot, readBack("webdav"))
    }

    /**
     * 非 BMP 字符（emoji）在 SQLite 的 length()/substr() 里算 1 个字符，
     * 在 Kotlin String.length 里算 2 个。offset 用错单位的话，每跨一次块边界
     * 就多跳 1 个字符，拼回来的 JSON 直接解析失败，然后掉进「首次同步」分支。
     * 这里把 emoji 撒在第一、二块里，两个边界都压到。
     */
    @Test
    fun chunkedReadKeepsSupplementaryCharacters() = runBlocking {
        val snapshot = buildString {
            append("{\"schema\":3,\"playlists\":[")
            repeat(100_000) { append('歌') }
            append("🎵")
            repeat(300_000) { append('歌') }
            append("🎶我的歌单")
            repeat(300_000) { append('歌') }
            append("]}")
        }
        dao.putBase(
            SyncBaseEntity(
                accountId = "webdav",
                snapshot = snapshot,
                remoteRevision = "r1",
                updatedAt = 0L,
            )
        )

        assertEquals(snapshot, readBack("webdav"))
    }

    private suspend fun readBack(accountId: String): String? =
        readChunkedText(dao.baseSnapshotLength(accountId)!!, CHUNK_CHARS) { offset, size ->
            dao.baseSnapshotChunk(accountId, offset, size)
        }

    private companion object {
        private const val CHUNK_CHARS = 256 * 1024
    }
}
