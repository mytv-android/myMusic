package com.mytv.mymusic.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.local.LOCAL_ORIGIN_MEDIA
import com.mytv.mymusic.data.local.LocalScanDiff
import com.mytv.mymusic.data.local.ScannedFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 9 → 10 只新建 local_tracks。exportSchema 关着，没有 v9 的 schema JSON，
 * 这里拿 v10 的库删掉新表、把 user_version 改回 9 来造一个“升级前”的库，再让 Room 走迁移打开：
 * Room 迁移后会逐列、逐索引校验表结构，SQL 和实体有一处对不上就直接抛异常；
 * 同时确认老表数据还在、新表能读写。
 */
@RunWith(AndroidJUnit4::class)
class LocalTracksMigrationTest {
    private val name = "local-tracks-migration-test.db"
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

    private fun track(id: String) = LocalScanDiff.merge(
        previous = null,
        file = ScannedFile(
            uri = "content://media/external/audio/media/$id",
            origin = LOCAL_ORIGIN_MEDIA,
            displayName = "$id.mp3",
            relativePath = "Music/",
            size = 4_000_000,
            modifiedAt = 1,
            mimeType = "audio/mpeg",
            title = "晴天",
            artist = "周杰伦",
            durationMs = 269_000,
        ),
        localId = "sha256:$id",
        now = 1,
    )

    @Test
    fun migrate9To10CreatesLocalTracksAndKeepsData() = runBlocking {
        open().apply {
            library().putPlaylist(PlaylistEntity(id = "p1", name = "夜间驾驶", createdAt = 1L, updatedAt = 1L))
            localTracks().upsert(listOf(track("old")))
            close()
        }
        // 退回 v9：没有 local_tracks
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP INDEX IF EXISTS idx_local_tracks_uri")
            raw.execSQL("DROP TABLE local_tracks")
            raw.version = 9
        }

        val db = open()
        try {
            // 第一次查询触发打开 → 迁移 → Room 校验表结构
            assertTrue(db.localTracks().all().isEmpty())
            assertEquals(listOf("p1"), db.library().playlistsOnce().map { it.id })

            db.localTracks().upsert(listOf(track("a"), track("b")))
            db.localTracks().setMissing(listOf("sha256:b"), true, 2)
            assertEquals(listOf("sha256:a", "sha256:b"), db.localTracks().all().map { it.localId }.sorted())
            assertEquals(1, db.localTracks().deleteMissing())

            val sqlite = db.openHelper.writableDatabase
            // 后面的迁移（10 → 11 …）也会接着走完，这里只要求升到了当前版本
            assertEquals(AppDatabase.ALL_MIGRATIONS.last().endVersion, sqlite.version)
            sqlite.query("PRAGMA index_list(local_tracks)").use { cursor ->
                val names = buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                assertTrue(names.toString(), "idx_local_tracks_uri" in names)
            }
        } finally {
            db.close()
        }
    }
}
