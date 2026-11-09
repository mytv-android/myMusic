package com.mytv.mymusic.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.local.LocalCloudCheck
import com.mytv.mymusic.data.local.LocalRefEntity
import com.mytv.mymusic.data.local.LocalRemoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 10 → 11 只新建 local_refs / local_remotes。和 [LocalTracksMigrationTest] 一样：拿 v11 的库删掉新表、
 * user_version 改回 10，再让 Room 走迁移打开——Room 打开时逐列校验表结构，迁移 SQL 和实体
 * （列名、类型、NOT NULL、复合主键）有一处对不上就抛异常；同时确认 v10 的数据（歌单、本地歌）还在。
 */
@RunWith(AndroidJUnit4::class)
class LocalRefsMigrationTest {
    private val name = "local-refs-migration-test.db"
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
    fun migrate10To11CreatesRefTablesAndKeepsData() = runBlocking {
        open().apply {
            library().putPlaylist(PlaylistEntity(id = "p1", name = "夜间驾驶", createdAt = 1L, updatedAt = 1L))
            localRefs().upsertRefs(listOf(LocalRefEntity(localId = "sha256:old", device = "Pixel 8")))
            close()
        }
        // 退回 v10：没有两张线索表
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE local_refs")
            raw.execSQL("DROP TABLE local_remotes")
            raw.version = 10
        }

        val db = open()
        try {
            // 第一次查询触发打开 → 迁移 → Room 校验表结构
            assertTrue(db.localRefs().refs(listOf("sha256:old")).isEmpty())
            assertEquals(listOf("p1"), db.library().playlistsOnce().map { it.id })
            assertTrue(db.localTracks().all().isEmpty())

            db.localRefs().upsertRefs(
                listOf(
                    LocalRefEntity(
                        localId = "sha256:a",
                        fileName = "晴天.flac",
                        fileSize = 31_400_000,
                        device = "Pixel 8",
                        matchProvider = "netease",
                        matchId = "186016",
                        matchConfidence = 0.94f,
                        matchMethod = "tag",
                        matchedAt = 5,
                    ),
                ),
            )
            // 同一首在两个云端各一行：复合主键（local_id, provider）
            db.localRefs().upsertRemotes(
                listOf(
                    LocalRemoteEntity(localId = "sha256:a", provider = "netease_cloud", remoteId = "c1", uploadedAt = "2026-11-08T12:00:00Z"),
                    LocalRemoteEntity(localId = "sha256:a", provider = "webdav", remoteId = "myMusic/music/ab/abc.flac"),
                ),
            )
            db.localRefs().setCheck("sha256:a", "webdav", LocalCloudCheck.MISSING, 9)
            val remotes = db.localRefs().remotes("sha256:a").sortedBy { it.provider }
            assertEquals(listOf("netease_cloud", "webdav"), remotes.map { it.provider })
            assertEquals(LocalCloudCheck.MISSING, remotes[1].checkState)
            assertEquals("2026-11-08T12:00:00Z", remotes[0].uploadedAt)
            assertEquals(0.94f, db.localRefs().ref("sha256:a")!!.matchConfidence, 0.0001f)

            db.localRefs().clearChecks("webdav")
            assertEquals(LocalCloudCheck.OK, db.localRefs().remote("sha256:a", "webdav")!!.checkState)

            assertEquals(11, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }
}
