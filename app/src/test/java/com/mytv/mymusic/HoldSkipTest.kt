package com.mytv.mymusic

import androidx.compose.ui.input.key.Key
import com.mytv.mymusic.ui.player.HoldSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 电视环境模式下按住左右键切歌：第一下只唤醒，长按再切一次，整次按住只切一首。 */
class HoldSkipTest {
    @Test
    fun firstPressOnlyWakes() {
        val skip = HoldSkip()
        skip.begin(Key.DirectionLeft)
        assertTrue(skip.tracking(Key.DirectionLeft))
        assertFalse(skip.tracking(Key.DirectionRight))
        assertNull(skip.progress(0, false))
    }

    @Test
    fun longPressSkipsOnceUntilRelease() {
        val skip = HoldSkip()
        skip.begin(Key.DirectionRight)
        assertEquals(1, skip.progress(0, longPress = true))
        assertNull(skip.progress(1, false))
        assertNull(skip.progress(2, true))
        skip.end()
        assertFalse(skip.tracking(Key.DirectionRight))
    }

    @Test
    fun repeatWithoutLongPressFlagStillSkips() {
        val skip = HoldSkip()
        skip.begin(Key.DirectionLeft)
        assertNull(skip.progress(0, false))
        assertEquals(-1, skip.progress(1, false))
    }

    @Test
    fun nextHoldStartsOver() {
        val skip = HoldSkip()
        skip.begin(Key.DirectionLeft)
        assertEquals(-1, skip.progress(1, true))
        skip.end()
        skip.begin(Key.DirectionLeft)
        assertEquals(-1, skip.progress(1, true))
    }
}
