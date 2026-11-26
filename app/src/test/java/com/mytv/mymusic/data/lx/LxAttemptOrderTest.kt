package com.mytv.mymusic.data.lx

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LxAttemptOrderTest {
    private fun record(id: String, vararg sources: String, enabled: Boolean = true) = LxSourceRecord(
        id = id,
        name = id,
        enabled = enabled,
        capabilities = sources.associateWith { LxCapability(actions = listOf("musicUrl")) },
    )

    @Test
    fun scriptOrderWrapsPlatformCandidates() {
        val kg = Song(id = "kg", name = "晴天", source = "kugou")
        val wy = Song(id = "wy", name = "晴天", source = "netease")
        val qq = Song(id = "qq", name = "晴天", source = "qq")
        val attempts = orderedLxAttempts(
            records = listOf(record("A", "kg", "tx"), record("B", "kg", "wy")),
            songs = listOf(wy, kg, qq),
        )
        assertEquals(
            listOf("A:kugou", "A:qq", "B:netease", "B:kugou"),
            attempts.map { (record, song) -> "${record.id}:${song.source}" },
        )
    }

    @Test
    fun disabledAndPreviouslyFailedScriptsAreSkippedPerSong() {
        val kg = Song(id = "kg", name = "晴天", source = "kugou")
        val wy = Song(id = "wy", name = "晴天", source = "netease")
        val attempts = orderedLxAttempts(
            records = listOf(record("A", "kg", "wy"), record("off", "kg", enabled = false), record("B", "kg", "wy")),
            songs = listOf(kg, wy, kg),
            isFailed = { songKey, recordId -> recordId == "A" && songKey == kg.key },
        )
        assertEquals(
            listOf("A:netease", "B:kugou", "B:netease"),
            attempts.map { (record, song) -> "${record.id}:${song.source}" },
        )
    }

    @Test
    fun desktopRecordAttemptsSongsFromAnyPlatform() {
        val apple = Song(id = "a", name = "晴天", source = "apple")
        val kg = Song(id = "kg", name = "晴天", source = "kugou")
        val desktop = LxSourceRecord(id = "D", name = "D", desktop = true)
        val attempts = orderedLxAttempts(records = listOf(desktop), songs = listOf(apple, kg))
        assertEquals(
            listOf("D:apple", "D:kugou"),
            attempts.map { (record, song) -> "${record.id}:${song.source}" },
        )
    }

    @Test
    fun nonDesktopRecordOnlyUsesDeclaredMusicUrlPlatforms() {
        val apple = Song(id = "a", name = "晴天", source = "apple")
        val kg = Song(id = "kg", name = "晴天", source = "kugou")
        val wy = Song(id = "wy", name = "晴天", source = "netease")
        val attempts = orderedLxAttempts(
            records = listOf(record("A", "kg"), LxSourceRecord(id = "B", name = "B")),
            songs = listOf(apple, kg, wy),
        )
        assertEquals(
            listOf("A:kugou"),
            attempts.map { (record, song) -> "${record.id}:${song.source}" },
        )
    }
}
