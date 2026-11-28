package com.mytv.mymusic

import com.mytv.mymusic.playback.APPLE_CROSSOVER
import com.mytv.mymusic.playback.albumTrackNumber
import com.mytv.mymusic.playback.appleCrossfadeGains
import com.mytv.mymusic.playback.crossfadeGains
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeTest {
    @Test
    fun `a crossfade starts fully on the old track and ends fully on the new one`() {
        val (outStart, inStart) = crossfadeGains(0f)
        assertEquals(1f, outStart, 0.001f)
        assertEquals(0f, inStart, 0.001f)

        val (outEnd, inEnd) = crossfadeGains(1f)
        assertEquals(0f, outEnd, 0.001f)
        assertEquals(1f, inEnd, 0.001f)
    }

    @Test
    fun `the middle keeps constant power so loudness does not dip`() {
        var previousPower = -1f
        for (step in 0..20) {
            val (out, incoming) = crossfadeGains(step / 20f)
            val power = out * out + incoming * incoming
            assertEquals(1f, power, 0.002f)
            assertTrue("outgoing gain should fall monotonically", out <= 1f)
            if (previousPower >= 0f) assertEquals(previousPower, power, 0.002f)
            previousPower = power
        }
    }

    @Test
    fun `values outside the transition are clamped`() {
        assertEquals(crossfadeGains(0f), crossfadeGains(-2f))
        assertEquals(crossfadeGains(1f), crossfadeGains(3f))
        assertEquals(appleCrossfadeGains(0f), appleCrossfadeGains(-2f))
        assertEquals(appleCrossfadeGains(1f), appleCrossfadeGains(3f))
    }

    @Test
    fun `a plain crossfade uses apple's exponential out and logarithmic in`() {
        val (outStart, inStart) = appleCrossfadeGains(0f)
        assertEquals(1f, outStart, 1e-6f)
        assertEquals(0f, inStart, 1e-6f)
        val (outEnd, inEnd) = appleCrossfadeGains(1f)
        assertEquals(0f, outEnd, 1e-6f)
        assertEquals(1f, inEnd, 1e-6f)
        // 正中间：上一首 (10^0.5 − 1) / 9，下一首 log2(1.5)
        val (outMid, inMid) = appleCrossfadeGains(0.5f)
        assertEquals(0.24025f, outMid, 1e-4f)
        assertEquals(0.58496f, inMid, 1e-4f)
        // 两边一样响的那一刻
        val (outCross, inCross) = appleCrossfadeGains(APPLE_CROSSOVER.toFloat())
        assertEquals(outCross, inCross, 1e-3f)
        assertEquals(0.41f, outCross, 0.01f)
        var lastOut = 2f
        var lastIn = -1f
        for (step in 0..20) {
            val (out, incoming) = appleCrossfadeGains(step / 20f)
            assertTrue(out <= lastOut)
            assertTrue(incoming >= lastIn)
            lastOut = out
            lastIn = incoming
        }
    }

    @Test
    fun `album positions are read from whichever key the platform uses`() {
        assertEquals(3, albumTrackNumber(mapOf("track" to "3"), "track", "track_number"))
        assertEquals(5, albumTrackNumber(mapOf("track_number" to "5"), "track", "track_number"))
        // 「3/12」这种带总数的写法取前面
        assertEquals(3, albumTrackNumber(mapOf("track" to " 3/12 "), "track"))
        assertNull(albumTrackNumber(mapOf("track" to "0"), "track"))
        assertNull(albumTrackNumber(mapOf("track" to "A1"), "track"))
        assertNull(albumTrackNumber(emptyMap(), "track", "track_number"))
    }
}
