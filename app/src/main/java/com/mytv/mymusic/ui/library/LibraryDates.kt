package com.mytv.mymusic.ui.library

import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.song
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 音乐库的日期文案。
 *
 * [DateTimeFormatter] 不可变且线程安全，可以同时被后台分组和主线程标签使用；
 * 替换掉了原先跨线程共享的 SimpleDateFormat。默认格式化器在进程启动时读取 locale，
 * 与旧实现一致；时区在调用时传入，测试可以固定为指定时区。
 */
internal object LibraryDates {
    val defaultDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月 d 日", Locale.getDefault())
    val defaultTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun today(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    /** 按本地日历日比较；不按“今天零点减 24 小时”，跨夏令时（23/25 小时的一天）也正确。 */
    fun dayLabel(
        millis: Long,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        formatter: DateTimeFormatter = defaultDayFormatter,
    ): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> formatter.format(date)
        }
    }

    fun timeLabel(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        formatter: DateTimeFormatter = defaultTimeFormatter,
    ): String = formatter.format(Instant.ofEpochMilli(millis).atZone(zone))
}

/** 一天的历史记录；label 已按“今天 / 昨天 / M 月 d 日”计算好。 */
internal class HistoryGroup(val label: String, val items: List<Pair<SavedSong, Song>>)

/** 历史页的后台准备结果：分组和播放队列一起产出，列表只负责展示。 */
internal class HistoryPrepared(val groups: List<HistoryGroup>, val queue: List<Song>) {
    companion object {
        val Empty = HistoryPrepared(emptyList(), emptyList())
    }
}

/**
 * 在后台解码历史 payload 并按本地日期分组。today 只在这里计算一次，
 * 避免旧实现每条记录都调用 dayLabel 并重复计算当天起点。
 */
internal fun prepareHistory(
    records: List<SavedSong>,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): HistoryPrepared {
    if (records.isEmpty()) return HistoryPrepared.Empty
    val today = LibraryDates.today(now, zone)
    val grouped = LinkedHashMap<String, MutableList<Pair<SavedSong, Song>>>()
    val queue = ArrayList<Song>(records.size)
    records.forEach { record ->
        val song = record.song()
        val label = LibraryDates.dayLabel(record.updatedAt, today, zone)
        grouped.getOrPut(label) { ArrayList() }.add(record to song)
        queue += song
    }
    return HistoryPrepared(grouped.map { HistoryGroup(it.key, it.value) }, queue)
}
