package com.mytv.mymusic.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytv.mymusic.data.subsonic.SubsonicTrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubsonicTracksMigrationTest {
    private val name = "subsonic-tracks-migration-test.db"
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
    fun migrate12To13CreatesTrackCacheAndKeepsLibrary() = runBlocking {
        open().apply {
            library().putPlaylist(PlaylistEntity(id = "p1", name = "Existing", createdAt = 1, updatedAt = 1))
            close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE subsonic_tracks")
            raw.version = 12
        }
        val db = open()
        try {
            assertEquals(listOf("p1"), db.library().playlistsOnce().map { it.id })
            db.subsonicTracks().putAll(
                listOf(
                    SubsonicTrackEntity("home", "s1", "Song", "Artist", "Album", "a1", 200, 1000, 320, "mp3", "cover", "{}", 1),
                    SubsonicTrackEntity("other", "s1", "Other", "Artist", "Album", "a2", 100, 500, 128, "mp3", "", "{}", 1),
                ),
            )
            assertEquals(listOf("s1"), db.subsonicTracks().search("home", "song", 20, 0).map { it.songId })
            db.subsonicTracks().deleteAccount("home")
            assertEquals(emptyList<SubsonicTrackEntity>(), db.subsonicTracks().list("home", 20, 0))
            assertEquals(1, db.subsonicTracks().list("other", 20, 0).size)
            assertEquals(AppDatabase.ALL_MIGRATIONS.last().endVersion, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }
}
