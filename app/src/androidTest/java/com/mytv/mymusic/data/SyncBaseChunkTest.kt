package com.mytv.mymusic.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

        val total = dao.baseSnapshotLength("webdav")!!
        val out = StringBuilder(total)
        var offset = 1 // SQLite substr 下标从 1 开始
        while (offset <= total) {
            val chunk = dao.baseSnapshotChunk("webdav", offset, 256 * 1024)!!
            if (chunk.isEmpty()) break
            out.append(chunk)
            offset += chunk.length
        }

        assertEquals(snapshot, out.toString())
    }
}
