package com.mytv.mymusic.ui

import com.mytv.mymusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistGroupingTest {
    private fun song(source: String, id: String = "1") = Song(id = id, source = source)

    @Test
    fun playingSongFollowsStreamingSource() {
        val original = song("netease", "n1")
        val playing = song("qq", "q1")
        // 普通换源：默认分组跟随实际取流源（和收藏写回原平台的规则不同，见 favoriteSourceSong）
        assertEquals("qq", defaultPlaylistGroup(playing, playing, "qq", original))
    }

    @Test
    fun playingThroughLxFallsBackToOriginalPlatform() {
        val original = song("netease", "n1")
        val playing = song("lx", "n1")
        // lx 只是取流通道，没有自己的平台歌单：回到切换前那首歌原本所属的平台
        assertEquals("netease", defaultPlaylistGroup(playing, playing, "lx", original))
    }

    @Test
    fun playingLocalFileGoesToLocalGroup() {
        val playing = song("local", "f1")
        assertEquals("", defaultPlaylistGroup(playing, playing, "local", playing))
    }

    @Test
    fun otherSongUsesItsOwnSource() {
        val playing = song("qq", "q1")
        val other = song("netease", "n2")
        // 列表里给别的歌点「加到歌单」时不借用当前播放的音源
        assertEquals("netease", defaultPlaylistGroup(other, playing, "qq", playing))
    }

    @Test
    fun otherLxSongKeepsLxCandidate() {
        // 不在播放的 lx 歌没有“切换前”可查：候选就是 lx，交给 resolve 兜底
        val lx = song("lx", "x1")
        assertEquals("lx", defaultPlaylistGroup(lx, null, null, null))
    }

    @Test
    fun unknownStreamingSourceUsesPrimary() {
        val original = song("kugou", "k1")
        val playing = song("kugou", "k1")
        // 取流源还没解析出来时按歌曲原本所属平台
        assertEquals("kugou", defaultPlaylistGroup(playing, playing, null, original))
    }

    @Test
    fun resolvePrefersCandidateWhenGroupExists() {
        assertEquals("qq", resolvePlaylistGroup("qq", listOf("", "netease", "qq")))
    }

    @Test
    fun resolveFallsBackToLocalWhenCandidateMissing() {
        assertEquals("", resolvePlaylistGroup("qq", listOf("", "netease")))
    }

    @Test
    fun resolveFallsBackToAllWhenLocalMissing() {
        assertNull(resolvePlaylistGroup("qq", listOf("netease")))
    }

    @Test
    fun resolvePrefersLocalForMissingCandidate() {
        assertEquals("", resolvePlaylistGroup(null, listOf("", "netease")))
        assertNull(resolvePlaylistGroup("", listOf("netease")))
    }
}
