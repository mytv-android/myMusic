package com.mytv.mymusic

import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.aggregate.TrackNormalizer
import com.mytv.mymusic.aggregate.VersionKind
import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    private val names = mapOf("netease" to "网易云音乐", "qq" to "QQ 音乐", "kugou" to "酷狗音乐", "kuwo" to "酷我音乐")
    private val cookies = emptyMap<String, String>()
    private val priority = listOf("netease", "qq", "kugou", "kuwo")

    private fun song(
        name: String,
        artist: String,
        album: String = "",
        duration: Int = 269,
        source: String = "netease",
        id: String = "$source-$name-$artist",
    ) = Song(id = id, name = name, artist = artist, album = album, duration = duration, source = source)

    @Test
    fun normalizesLiveVersionAndTitle() {
        val normalized = TrackNormalizer.normalize(song("晴天 (Live)", "周杰伦", "叶惠美", source = "qq"))
        assertEquals("晴天", normalized.titleKey)
        assertEquals(VersionKind.Live, normalized.version)
        assertEquals(listOf("周杰伦"), normalized.artists)
    }

    @Test
    fun movesFeatFromTitleToArtistSet() {
        val normalized = TrackNormalizer.normalize(song("七里香 (feat. 弹头)", "周杰伦"))
        assertEquals("七里香", normalized.titleKey)
        assertTrue(normalized.artistKeys.contains("周杰伦"))
        assertTrue(normalized.artistKeys.contains("弹头"))
    }

    @Test
    fun normalizesFullWidthText() {
        val normalized = TrackNormalizer.normalize(song("ＳＨＡＰＥ ＯＦ ＹＯＵ", "Ed Sheeran", source = "qq"))
        assertEquals("shapeofyou", normalized.titleKey)
        assertEquals("edsheeran", normalized.primaryArtistKey)
    }

    @Test
    fun sameRecordingScoresHigh() {
        val score = TrackMatcher.score(
            song("晴天", "周杰伦", "叶惠美"),
            song("晴天", "周杰伦", "叶惠美", source = "qq"),
        )
        assertTrue(score.total >= 0.92f)
    }

    @Test
    fun liveVersionIsNotMergedAutomatically() {
        val groups = TrackMatcher.group(
            mapOf(
                "netease" to listOf(song("晴天", "周杰伦", "叶惠美", 269)),
                "qq" to listOf(song("晴天 (Live)", "周杰伦", "世界巡回演唱会", 312, source = "qq")),
            ),
            names,
            cookies,
            0.92f,
            priority,
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun featVersionIsMerged() {
        val groups = TrackMatcher.group(
            mapOf(
                "netease" to listOf(song("七里香", "周杰伦", "七里香", 299)),
                "qq" to listOf(song("七里香 (feat. 弹头)", "周杰伦", "七里香", 299, source = "qq")),
            ),
            names,
            cookies,
            0.92f,
            priority,
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().sources.size)
    }

    @Test
    fun differentArtistWithSameTitleIsNotMerged() {
        val groups = TrackMatcher.group(
            mapOf(
                "netease" to listOf(song("起风了", "买辣椒也用券", "起风了", 325)),
                "qq" to listOf(song("起风了", "吴青峰", "起风了", 325, source = "qq")),
            ),
            names,
            cookies,
            0.92f,
            priority,
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun largeDurationDifferenceIsNotMerged() {
        val groups = TrackMatcher.group(
            mapOf(
                "netease" to listOf(song("晴天", "周杰伦", "叶惠美", 269)),
                "qq" to listOf(song("晴天 (Extended)", "周杰伦", "叶惠美", 430, source = "qq")),
            ),
            names,
            cookies,
            0.92f,
            priority,
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun manualSplitPreventsAutomaticMerge() {
        val netease = song("晴天", "周杰伦", "叶惠美", 269)
        val qq = song("晴天", "周杰伦", "叶惠美", 269, source = "qq")
        val first = TrackMatcher.group(
            mapOf("netease" to listOf(netease), "qq" to listOf(qq)),
            names,
            cookies,
            0.92f,
            priority,
        )
        assertEquals(1, first.size)
        val split = TrackMatcher.group(
            mapOf("netease" to listOf(netease), "qq" to listOf(qq)),
            names,
            cookies,
            0.92f,
            priority,
            setOf("${qq.key}|${first.single().id}"),
        )
        assertEquals(2, split.size)
    }

    @Test
    fun manualMergeOverridesVersionSeparation() {
        val studio = song("晴天", "周杰伦", "叶惠美", 269)
        val live = song("晴天 (Live)", "周杰伦", "世界巡回演唱会", 312, source = "qq")
        val first = TrackMatcher.group(
            mapOf("netease" to listOf(studio), "qq" to listOf(live)),
            names,
            cookies,
            0.92f,
            priority,
        )
        assertEquals(2, first.size)
        val merged = TrackMatcher.group(
            mapOf("netease" to listOf(studio), "qq" to listOf(live)),
            names,
            cookies,
            0.92f,
            priority,
            mergeTargets = mapOf(live.key to studio.key),
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().sources.size)
    }

    @Test
    fun singleSourceCanonicalTrackIsUsable() {
        val track = TrackMatcher.singleSource(song("晴天", "周杰伦", "叶惠美"), "网易云音乐", "netease")
        assertEquals("晴天", track.title)
        assertEquals(1, track.sources.size)
        assertTrue(track.id.startsWith("ct_"))
    }
}
