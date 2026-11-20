package com.mytv.mymusic.playback

import com.mytv.mymusic.data.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackOriginTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun originSurvivesTheQueueSnapshotRoundTrip() {
        val origin = PlaybackOrigin("collection", "playlist:netease:42", "私人雷达")
        val saved = SavedQueue(listOf(Song(id = "347230", name = "晴天", source = "netease")), 123, origin)

        val restored = json.decodeFromString(SavedQueue.serializer(), json.encodeToString(SavedQueue.serializer(), saved))

        assertEquals(origin, restored.origin)
        assertEquals("347230", restored.songs.single().id)
    }

    @Test
    fun oldQueueSnapshotsAndExplicitEmptyOriginStaySupported() {
        val old = json.decodeFromString(SavedQueue.serializer(), """{"songs":[],"savedAt":1}""")
        val cleared = json.decodeFromString(SavedQueue.serializer(), """{"songs":[],"origin":null}""")

        assertNull(old.origin)
        assertNull(cleared.origin)
    }

    @Test
    fun localPlaylistOriginKeepsItsStableDatabaseId() {
        val origin = PlaybackOrigin("local", "7f0c3d4a", "通勤")

        val restored = json.decodeFromString(PlaybackOrigin.serializer(), json.encodeToString(PlaybackOrigin.serializer(), origin))

        assertEquals("7f0c3d4a", restored.key)
        assertEquals("通勤", restored.title)
    }
}
