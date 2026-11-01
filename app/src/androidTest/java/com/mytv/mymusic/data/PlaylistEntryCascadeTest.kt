package com.mytv.mymusic.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * playlists 和 playlist_entries 是 ON DELETE CASCADE 外键，而 Room 的
 * @Insert(OnConflictStrategy.REPLACE) 会先删旧行再插入 —— 用 REPLACE 写歌单行会把整份曲目
 * 连带删掉。这里守住 putPlaylist 必须是原地更新：刷新绑定歌单、解除绑定之后歌曲还在，
 * 而硬删除歌单仍然级联清掉曲目。
 */
@RunWith(AndroidJUnit4::class)
class PlaylistEntryCascadeTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.library()
    }

    @After
    fun tearDown() = db.close()

    private fun playlist(id: String, bound: Boolean) = PlaylistEntity(
        id = id,
        name = "歌单",
        createdAt = 1L,
        updatedAt = 1L,
        linkProvider = if (bound) "netease" else "",
        linkId = if (bound) "123" else "",
        linkCover = if (bound) "cover" else "",
        linkSyncedAt = if (bound) 1L else 0L,
    )

    private fun entries(id: String, count: Int) =
        (0 until count).map { index ->
            PlaylistEntryEntity(id, index, "key$index", """{"id":"$index","name":"歌 $index"}""")
        }

    @Test
    fun putPlaylistKeepsEntries() = runBlocking {
        dao.putPlaylist(playlist("p1", bound = true))
        dao.replaceEntries("p1", entries("p1", 5))
        assertEquals(5, dao.entriesOnce("p1").size)

        // 刷新绑定歌单：先写曲目，再把同步时间写回歌单行
        dao.replaceEntries("p1", entries("p1", 3))
        dao.putPlaylist(playlist("p1", bound = true).copy(linkSyncedAt = 99L, updatedAt = 99L, dirty = true))

        assertEquals(3, dao.entriesOnce("p1").size)
        assertEquals(99L, dao.playlistOnce("p1")?.linkSyncedAt)
    }

    @Test
    fun unbindKeepsEntries() = runBlocking {
        dao.putPlaylist(playlist("p1", bound = true))
        dao.replaceEntries("p1", entries("p1", 4))

        // 解除绑定：保留当前歌曲，只清 link_* 字段
        val current = dao.playlistOnce("p1")!!
        dao.putPlaylist(current.copy(linkProvider = "", linkId = "", linkCover = "", linkSyncedAt = 0L))

        assertEquals(4, dao.entriesOnce("p1").size)
        assertFalse(dao.playlistOnce("p1")?.bound ?: true)
    }

    @Test
    fun hardDeleteStillCascades() = runBlocking {
        dao.putPlaylist(playlist("p1", bound = false))
        dao.replaceEntries("p1", entries("p1", 2))

        dao.deletePlaylist("p1")

        assertEquals(0, dao.entriesOnce("p1").size)
    }
}
