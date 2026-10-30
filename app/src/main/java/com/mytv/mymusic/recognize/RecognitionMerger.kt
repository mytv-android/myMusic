package com.mytv.mymusic.recognize

import com.mytv.mymusic.data.Song

/**
 * 两个通道轮流取结果，按“歌名 + 歌手”去重并保留先出现的那个。
 * 两个平台对同一首歌的版本可能不同，去重后只留一条；换源由播放器自己的匹配负责。
 */
object RecognitionMerger {
    private val SEPARATORS = Regex("[\\s\\p{Punct}·・、，。！？：；“”‘’（）《》]+")

    fun merge(netease: List<Song>, kugou: List<Song>, limit: Int = 8): List<Song> {
        val seen = HashSet<String>()
        val merged = ArrayList<Song>(limit)
        val rows = maxOf(netease.size, kugou.size)
        for (index in 0 until rows) {
            for (song in listOfNotNull(netease.getOrNull(index), kugou.getOrNull(index))) {
                val key = normalize(song.name) + "|" + normalize(song.artist)
                if (!seen.add(key)) continue
                merged += song
                if (merged.size >= limit) return merged
            }
        }
        return merged
    }

    internal fun normalize(value: String): String =
        value.lowercase().replace(SEPARATORS, "")
}
