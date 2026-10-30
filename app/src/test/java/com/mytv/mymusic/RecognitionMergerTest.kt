package com.mytv.mymusic

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.recognize.RecognitionMerger
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionMergerTest {
    private fun song(name: String, artist: String, source: String) =
        Song(id = "$source-$name", name = name, artist = artist, source = source)

    @Test
    fun `interleaves channels and drops duplicates across sources`() {
        val netease = listOf(
            song("小苹果", "筷子兄弟", "netease"),
            song("最幸福的人", "曾春年", "netease"),
        )
        val kugou = listOf(
            song("小苹果", "筷子兄弟", "kugou"),
            song("义勇军进行曲", "中国人民解放军军乐团", "kugou"),
        )
        assertEquals(
            listOf("netease-小苹果", "netease-最幸福的人", "kugou-义勇军进行曲"),
            RecognitionMerger.merge(netease, kugou).map { it.id },
        )
    }

    @Test
    fun `different singers of the same title are kept`() {
        val netease = listOf(song("小苹果", "筷子兄弟", "netease"))
        val kugou = listOf(song("小苹果", "儿歌点点", "kugou"))
        assertEquals(2, RecognitionMerger.merge(netease, kugou).size)
    }

    @Test
    fun `punctuation and spacing do not create duplicates`() {
        val netease = listOf(song("小苹果（Live）", "筷子兄弟", "netease"))
        val kugou = listOf(song("小苹果 (live)", "筷子兄弟", "kugou"))
        assertEquals(1, RecognitionMerger.merge(netease, kugou).size)
    }

    @Test
    fun `limit caps the merged list`() {
        val netease = (1..5).map { song("歌$it", "歌手", "netease") }
        val kugou = (1..5).map { song("酷狗歌$it", "歌手", "kugou") }
        assertEquals(4, RecognitionMerger.merge(netease, kugou, limit = 4).size)
    }
}
