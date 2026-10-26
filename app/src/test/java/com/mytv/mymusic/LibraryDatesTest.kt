package com.mytv.mymusic

import com.mytv.mymusic.data.DownloadRecord
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.library.DOWNLOAD_DONE
import com.mytv.mymusic.ui.library.DownloadDiskStat
import com.mytv.mymusic.ui.library.DownloadSnapshot
import com.mytv.mymusic.ui.library.prepareDownloads
import com.mytv.mymusic.ui.library.LibraryDates
import com.mytv.mymusic.ui.library.prepareDownloadSections
import com.mytv.mymusic.ui.library.prepareHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 历史分组的日期语义：按本地日历日比较，跨夏令时也保持“今天 / 昨天 / 更早”；
 * 同时验证后台准备数据（分组、播放队列）和下载页分组不受纯 payload 变化影响。
 */
class LibraryDatesTest {
    private val zone = ZoneId.of("America/New_York")
    private val dayFormatter = DateTimeFormatter.ofPattern("M 月 d 日", Locale.US)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun saved(key: String, updatedAt: Long): SavedSong = SavedSong(
        key = "test:$key",
        bucket = "history",
        payload = """{"id":"$key","name":"Song $key","source":"test"}""",
        updatedAt = updatedAt,
    )

    @Test
    fun dayLabelStillCallsYesterdayAfterFallBackDst() {
        // 2026-11-01 是 25 小时的一天。旧实现用“今天零点减 24 小时”，
        // 会把 11 月 1 日凌晨算成更早的日期。
        val now = millis(2026, 11, 2, 12, 0)
        val record = millis(2026, 11, 1, 0, 30)
        val today = LocalDate.of(2026, 11, 2)
        val oldThreshold = ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, zone)
            .toInstant().toEpochMilli() - 24L * 60 * 60 * 1000

        assertTrue("测试前提：记录早于旧的 24 小时阈值", record < oldThreshold)
        assertEquals("昨天", LibraryDates.dayLabel(record, today, zone, dayFormatter))
        assertEquals("今天", LibraryDates.dayLabel(now, today, zone, dayFormatter))
    }

    @Test
    fun dayLabelDoesNotCallPreviousDayYesterdayAfterSpringForwardDst() {
        // 2026-03-08 是 23 小时的一天：今天零点减 24 小时会落到 3 月 7 日 23 点，
        // 旧实现会把 3 月 7 日深夜误标成“昨天”。
        val now = millis(2026, 3, 9, 12, 0)
        val record = millis(2026, 3, 7, 23, 30)
        val today = LocalDate.of(2026, 3, 9)
        val oldThreshold = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, zone)
            .toInstant().toEpochMilli() - 24L * 60 * 60 * 1000

        assertTrue("测试前提：旧的 24 小时阈值已越过本地零点", record >= oldThreshold)
        assertEquals("3 月 7 日", LibraryDates.dayLabel(record, today, zone, dayFormatter))
        assertNotEquals("昨天", LibraryDates.dayLabel(record, today, zone, dayFormatter))
        assertEquals("昨天", LibraryDates.dayLabel(millis(2026, 3, 8, 0, 10), today, zone, dayFormatter))
    }

    @Test
    fun dayLabelKeepsTodayYesterdayAndOlderSemantics() {
        val today = LocalDate.of(2026, 10, 25)
        assertEquals("今天", LibraryDates.dayLabel(millis(2026, 10, 25, 8, 0), today, zone, dayFormatter))
        assertEquals("昨天", LibraryDates.dayLabel(millis(2026, 10, 24, 23, 50), today, zone, dayFormatter))
        assertEquals("10 月 20 日", LibraryDates.dayLabel(millis(2026, 10, 20, 7, 0), today, zone, dayFormatter))
    }

    @Test
    fun timeLabelUsesRequestedZone() {
        val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        val instant = Instant.parse("2026-10-25T13:05:00Z").toEpochMilli()
        assertEquals("13:05", LibraryDates.timeLabel(instant, ZoneId.of("UTC"), clock))
        assertEquals("21:05", LibraryDates.timeLabel(instant, ZoneId.of("Asia/Shanghai"), clock))
    }

    @Test
    fun prepareHistoryGroupsByLocalDayAndBuildsQueueInOrder() {
        val now = millis(2026, 10, 25, 12, 0)
        val records = listOf(
            saved("a", millis(2026, 10, 25, 9, 0)),
            saved("b", millis(2026, 10, 24, 22, 0)),
            saved("c", millis(2026, 10, 20, 8, 0)),
            saved("d", millis(2026, 10, 24, 0, 5)),
        )

        val prepared = prepareHistory(records, now, zone)

        assertEquals(listOf("今天", "昨天", "10 月 20 日"), prepared.groups.map { it.label })
        assertEquals(listOf(1, 2, 1), prepared.groups.map { it.items.size })
        assertEquals(listOf("test:a", "test:b", "test:c", "test:d"), prepared.queue.map { it.key })
        assertEquals(listOf("Song a", "Song b", "Song c", "Song d"), prepared.queue.map { it.title })
        assertEquals(listOf("test:b", "test:d"), prepared.groups[1].items.map { it.first.key })
    }

    @Test
    fun prepareHistoryHandlesEmptyInput() {
        val prepared = prepareHistory(emptyList(), millis(2026, 10, 25, 12, 0), zone)
        assertTrue(prepared.groups.isEmpty())
        assertTrue(prepared.queue.isEmpty())
    }

    @Test
    fun downloadSnapshotReusesDiskButRefreshesFreeSpaceForActiveChanges() {
        val done = DownloadRecord("test:done", """{"id":"done","name":"Done","source":"test"}""", "/done.flac", DOWNLOAD_DONE, 1L)
        val active = DownloadRecord("test:active", """{"id":"active","name":"Active","source":"test"}""", "/active.flac", "下载中", 2L)
        var probes = 0
        val first = prepareDownloads(listOf(done), DownloadSnapshot(), { probes++; DownloadDiskStat(1_000L, true) }, { 9_000L })
        val next = prepareDownloads(listOf(done, active), first, { probes++; DownloadDiskStat(0L, false) }, { 8_000L })

        assertEquals(1, probes)
        assertEquals(1_000L, next.sections.doneBytes)
        assertEquals(8_000L, next.freeBytes)
        assertFalse(next.sections.done.single().missing)
        assertEquals(listOf(active), next.sections.active.map { it.record })
    }

    @Test
    fun downloadSnapshotPublishesMatchingRecordsAndFileStats() {
        val record = DownloadRecord("test:done", """{"id":"done","name":"Done","source":"test"}""", "/old.flac", DOWNLOAD_DONE, 1L)
        val first = prepareDownloads(listOf(record), DownloadSnapshot(), { DownloadDiskStat(1_000L, true) }, { 9_000L })
        val changed = record.copy(path = "/missing.flac", updatedAt = 2L)
        val next = prepareDownloads(listOf(changed), first, { path ->
            assertEquals("/missing.flac", path)
            DownloadDiskStat(0L, false)
        }, { 10_000L })
        assertEquals(changed, next.sections.done.single().record)
        assertTrue(next.sections.done.single().missing)
        assertEquals(setOf("/missing.flac"), next.disk.keys)
        assertEquals(0L, next.sections.doneBytes)
        val empty = prepareDownloads(emptyList(), next, { error("没有已完成文件，不应探测磁盘") }, { 10_000L })
        assertTrue(empty.sections.done.isEmpty())
        assertTrue(empty.disk.isEmpty())
    }

    @Test
    fun prepareDownloadSectionsPartitionsAndSumsCompletedFiles() {
        val done = DownloadRecord(
            key = "test:done",
            payload = "{}",
            path = "/downloads/done.flac",
            state = DOWNLOAD_DONE,
            updatedAt = 1L,
        )
        val missing = DownloadRecord(
            key = "test:missing",
            payload = "{}",
            path = "/downloads/missing.flac",
            state = DOWNLOAD_DONE,
            updatedAt = 2L,
        )
        val active = DownloadRecord(
            key = "test:active",
            payload = "{}",
            path = "/downloads/active.flac",
            state = "下载中",
            updatedAt = 3L,
        )
        val decoded = listOf(
            done to Song(id = "done", name = "Done", source = "test"),
            missing to Song(id = "missing", name = "Missing", source = "test"),
            active to Song(id = "active", name = "Active", source = "test"),
        )
        val disk = mapOf(
            done.path to DownloadDiskStat(size = 1_000L, exists = true),
            missing.path to DownloadDiskStat(size = 500L, exists = false),
        )

        val sections = prepareDownloadSections(decoded, disk)

        assertEquals(listOf(done, missing), sections.done.map { it.record })
        assertEquals(listOf(active), sections.active.map { it.record })
        assertEquals(1_500L, sections.doneBytes)
        assertEquals(1_000L, sections.done[0].size)
        assertFalse(sections.done[0].missing)
        assertEquals(500L, sections.done[1].size)
        assertTrue(sections.done[1].missing)
        // 活跃下载不读磁盘
        assertEquals(0L, sections.active[0].size)
        assertFalse(sections.active[0].missing)
    }
}
