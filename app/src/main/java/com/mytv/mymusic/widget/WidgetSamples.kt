package com.mytv.mymusic.widget

import com.mytv.mymusic.data.ListeningMode
import com.mytv.mymusic.data.Song

/** 组件选择器预览（Android 15 起实时生成）用的示例数据：不读用户的播放记录，封面显示占位。 */
object WidgetSamples {
    private val songs = listOf(
        Song(id = "1", name = "但愿人长久", artist = "王菲", source = "sample", duration = 283),
        Song(id = "2", name = "夜空中最亮的星", artist = "逃跑计划", source = "sample", duration = 252),
        Song(id = "3", name = "后来", artist = "刘若英", source = "sample", duration = 341),
        Song(id = "4", name = "晴天", artist = "周杰伦", source = "sample", duration = 269),
        Song(id = "5", name = "平凡之路", artist = "朴树", source = "sample", duration = 301),
        Song(id = "6", name = "小幸运", artist = "田馥甄", source = "sample", duration = 265),
    )

    val list: List<Song> get() = songs

    private const val LYRICS = """[00:00.00]但愿人长久
[02:00.00]明月几时有
[02:06.00]把酒问青天
[02:12.00]不知天上宫阙
[02:18.00]今夕是何年
[02:24.00]我欲乘风归去"""

    val playback: PlaybackSnapshot
        get() = PlaybackSnapshot(
            song = songs[0],
            index = 0,
            queueSize = songs.size,
            playing = true,
            live = true,
            positionMs = 128_000L,
            anchorAt = Long.MAX_VALUE,
            durationMs = 283_000L,
            lyrics = LYRICS,
            lyricsReady = true,
            sourceName = "网易云",
            quality = "无损",
            hasPrevious = true,
            hasNext = true,
        )

    val mode = ListeningMode(id = "sample", name = "默认模式", subtitle = "按你的口味推荐", source = "netease", group = "私人漫游")

    val chart: ChartSnapshot
        get() = ChartSnapshot(source = "sample", id = "sample", name = "飙升榜", sourceName = "网易云", songs = songs, updatedAt = System.currentTimeMillis())
}
