package com.mytv.mymusic

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.VolumeNormalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeNormalizationTest {
    private fun song(source: String, vararg extra: Pair<String, String>) =
        Song(id = "id", name = "name", source = source, extra = mapOf(*extra))

    @Test
    fun `netease prefers closed gain`() {
        val gain = VolumeNormalization.gainDb(
            song(
                "netease",
                "netease_gain" to "-3.0",
                "netease_peak" to "0.5",
                "netease_closed_gain" to "-5.0",
                "netease_closed_peak" to "0.5",
            )
        )
        assertEquals(-5f, gain, 0.01f)
    }

    @Test
    fun `netease falls back to plain gain when closed gain is zero`() {
        val gain = VolumeNormalization.gainDb(
            song(
                "netease",
                "netease_gain" to "-3.0",
                "netease_peak" to "0.5",
                "netease_closed_gain" to "0.0",
            )
        )
        assertEquals(-3f, gain, 0.01f)
    }

    @Test
    fun `kugou recomputes to target and limits boost by peak`() {
        val gain = VolumeNormalization.gainDb(
            song(
                "kugou",
                "kugou_volume" to "-18.0",
                "kugou_volume_gain" to "1.0",
                "kugou_volume_peak" to "0.9",
            )
        )
        // -14 - (-18) + 1 = +5 dB，峰值 0.9 只允许 +0.74 dB
        assertEquals(0.74f, gain, 0.03f)
    }

    @Test
    fun `qq applies gain directly`() {
        val gain = VolumeNormalization.gainDb(
            song("qq", "qq_gain" to "-7.603", "qq_peak" to "1.0")
        )
        assertEquals(-7.603f, gain, 0.01f)
    }

    @Test
    fun `qianqian uses mean volume and max volume peak`() {
        val gain = VolumeNormalization.gainDb(
            song(
                "qianqian",
                "qianqian_mean_volume" to "-24.1",
                "qianqian_max_volume" to "-2.2",
                "qianqian_replay_gain" to "3.82",
            )
        )
        // -14 - (-24.1) = +10.1 dB，峰值 -2.2 dBFS 允许 +2.02 dB
        assertEquals(2.02f, gain, 0.03f)
    }

    @Test
    fun `qianqian falls back to replay gain without mean volume`() {
        val gain = VolumeNormalization.gainDb(
            song(
                "qianqian",
                "qianqian_mean_volume" to "0.0",
                "qianqian_max_volume" to "0.0",
                "qianqian_replay_gain" to "3.82",
            )
        )
        assertEquals(3.82f, gain, 0.01f)
    }

    @Test
    fun `unknown songs are untouched`() {
        assertEquals(0f, VolumeNormalization.gainDb(song("kuwo")), 0.001f)
        assertEquals(0f, VolumeNormalization.gainDb(null), 0.001f)
    }

    @Test
    fun `boost and cut are clamped`() {
        assertEquals(6f, VolumeNormalization.gainDb(song("qq", "qq_gain" to "20")), 0.01f)
        assertEquals(-20f, VolumeNormalization.gainDb(song("qq", "qq_gain" to "-50")), 0.01f)
    }

    @Test
    fun `metadata presence decides the fallback`() {
        assertFalse(VolumeNormalization.hasMetadata(null))
        assertFalse(VolumeNormalization.hasMetadata(song("kuwo")))
        assertTrue(VolumeNormalization.hasMetadata(song("qq", "qq_gain" to "0.0")))
        assertTrue(
            VolumeNormalization.hasMetadata(
                song("netease", "netease_gain" to "0.0", "netease_peak" to "1.0")
            )
        )
        assertFalse(
            VolumeNormalization.hasMetadata(
                song(
                    "qianqian",
                    "qianqian_mean_volume" to "0.0",
                    "qianqian_replay_gain" to "0.0",
                )
            )
        )
        assertTrue(
            VolumeNormalization.hasMetadata(
                song("qianqian", "qianqian_mean_volume" to "-20.0")
            )
        )
        assertFalse(VolumeNormalization.hasMetadata(song("kugou", "kugou_volume" to "0.0")))
    }
}
