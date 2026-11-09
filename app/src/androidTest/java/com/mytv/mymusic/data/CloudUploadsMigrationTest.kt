package com.mytv.mymusic.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.local.LocalRefEntity
import com.mytv.mymusic.data.local.LocalUploadEntity
import com.mytv.mymusic.data.local.LocalUploadRows
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 11 → 12 只新建 cloud_uploads（上传队列落盘）。做法同 [LocalRefsMigrationTest]：拿 v12 的库删掉新表、
 * user_version 改回 11，再让 Room 走迁移打开——迁移 SQL 和 [LocalUploadEntity]（列名、类型、NOT NULL、
 * 复合主键）有一处对不上 Room 就抛异常；同时确认 v11 的数据（歌单、本地歌线索）还在。
 */
@RunWith(AndroidJUnit4::class)
class CloudUploadsMigrationTest {
    private val name = "cloud-uploads-migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    @Test
    fun migrate11To12CreatesUploadTableAndKeepsData() = runBlocking {
        open().apply {
            library().putPlaylist(PlaylistEntity(id = "p1", name = "夜间驾驶", createdAt = 1L, updatedAt = 1L))
            localRefs().upsertRefs(listOf(LocalRefEntity(localId = "sha256:old", device = "Pixel 8")))
            localUploads().replace("webdav", listOf(LocalUploadEntity("sha256:old", "webdav", 0, LocalUploadRows.QUEUED)))
            close()
        }
        // 退回 v11：没有上传队列表
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE cloud_uploads")
            raw.version = 11
        }

        val db = open()
        try {
            // 第一次查询触发打开 → 迁移 → Room 校验表结构
            assertTrue(db.localUploads().all().isEmpty())
            assertEquals(listOf("p1"), db.library().playlistsOnce().map { it.id })
            assertEquals("Pixel 8", db.localRefs().ref("sha256:old")!!.device)

            // 同一首可以同时排在两个云端：复合主键（local_id, provider）
            db.localUploads().replace(
                "netease_cloud",
                listOf(
                    LocalUploadEntity("sha256:a", "netease_cloud", 0, LocalUploadRows.QUEUED, "RiskControl", "网易云说网络环境有风险，先暂停上传", -460, 2),
                    LocalUploadEntity("sha256:b", "netease_cloud", 1, LocalUploadRows.FAILED, "TranscodeTimeout", "转码超时", 0, 1),
                ),
            )
            db.localUploads().replace("webdav", listOf(LocalUploadEntity("sha256:a", "webdav", 0, LocalUploadRows.QUEUED)))
            val rows = db.localUploads().all()
            assertEquals(listOf("netease_cloud" to "sha256:a", "netease_cloud" to "sha256:b", "webdav" to "sha256:a"), rows.map { it.provider to it.localId })
            assertEquals(-460, rows[0].errorCode)
            assertEquals(2, rows[0].attempts)
            assertEquals(LocalUploadRows.FAILED, rows[1].state)

            // 整个云端的队列换掉：旧行不留孤行，另一个云端不受影响
            db.localUploads().replace("netease_cloud", listOf(LocalUploadEntity("sha256:b", "netease_cloud", 0, LocalUploadRows.QUEUED)))
            assertEquals(listOf("sha256:b", "sha256:a"), db.localUploads().all().map { it.localId })
            db.localUploads().replace("netease_cloud", emptyList())
            assertEquals(listOf("webdav"), db.localUploads().all().map { it.provider })

            // 只要求升到了当前版本：以后再加版本不用改这里
            assertEquals(AppDatabase.ALL_MIGRATIONS.last().endVersion, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }
}
