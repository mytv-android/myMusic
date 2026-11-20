package com.mytv.mymusic.data.metadata

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityMetadataTest {
    private val perfect = Song(
        id = "1193701400",
        name = "Perfect",
        artist = "Ed Sheeran",
        album = "÷ (Deluxe)",
        duration = 263,
        source = "apple",
    )

    @Test
    fun lrclibPrefersSyncedLyricsFromTheMatchingRecording() {
        val entries = parseLrcLib(
            """
            [
              {"id":1,"trackName":"Another Song","artistName":"Someone Else","duration":100,"plainLyrics":"wrong"},
              {"id":2,"trackName":"Perfect","artistName":"Ed Sheeran","albumName":"÷ (Deluxe)","duration":263.4,"plainLyrics":"plain","syncedLyrics":"[00:01.00]synced"}
            ]
            """.trimIndent(),
        )
        assertEquals("[00:01.00]synced", selectLrcLib(perfect, entries))
    }

    @Test
    fun lrclibDoesNotFillLyricsFromAnUnrelatedSong() {
        val entries = listOf(
            LrcLibEntry(id = 1, trackName = "Another Song", artistName = "Someone Else", duration = 100.0, plainLyrics = "wrong"),
        )
        assertTrue(selectLrcLib(perfect, entries).isEmpty())
    }

    @Test
    fun appleCoverUsesTheMatchedTrackAndLargeArtwork() {
        val entries = parseAppleSearch(
            """
            {"results":[{
              "trackId":1193701400,"trackName":"Perfect","artistName":"Ed Sheeran",
              "collectionName":"÷ (Deluxe)","trackTimeMillis":263400,
              "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/art.jpg/100x100bb.jpg"
            }]}
            """.trimIndent(),
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/art.jpg/1200x1200bb.jpg",
            selectAppleCover(perfect, entries),
        )
    }

    @Test
    fun duplicateOrMissingServiceIdsCannotSelectTheWrongLyricEntry() {
        val entries = listOf(
            LrcLibEntry(trackName = "Perfect", artistName = "Someone Else", duration = 100.0, plainLyrics = "wrong"),
            LrcLibEntry(trackName = "Perfect", artistName = "Ed Sheeran", albumName = "÷ (Deluxe)", duration = 263.0, syncedLyrics = "right"),
        )
        assertEquals("right", selectLrcLib(perfect, entries))
    }

    @Test
    fun instrumentalEntryDoesNotProduceEmptyLyrics() {
        val entries = listOf(LrcLibEntry(id = 1, trackName = "Perfect", artistName = "Ed Sheeran", instrumental = true))
        assertTrue(selectLrcLib(perfect, entries).isEmpty())
    }
}
