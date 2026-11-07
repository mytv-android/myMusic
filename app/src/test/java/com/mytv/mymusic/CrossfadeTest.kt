package com.mytv.mymusic

import com.mytv.mymusic.playback.crossfadeGains
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
    }
}
