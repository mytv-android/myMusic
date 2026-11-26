package com.mytv.mymusic

import com.mytv.mymusic.data.RepeatMode
import com.mytv.mymusic.ui.dormantTargetIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 休眠（播放器还空着）时按上一首 / 下一首该接第几首。 */
class DormantTargetIndexTest {
    @Test
    fun `next and previous move through the middle`() {
        assertEquals(4, dormantTargetIndex(3, 6, forward = true, repeat = RepeatMode.All))
        assertEquals(2, dormantTargetIndex(3, 6, forward = false, repeat = RepeatMode.All))
        assertEquals(4, dormantTargetIndex(3, 6, forward = true, repeat = RepeatMode.Off))
    }

    @Test
    fun `next on the last song wraps only when repeating`() {
        assertEquals(0, dormantTargetIndex(5, 6, forward = true, repeat = RepeatMode.All))
        assertNull(dormantTargetIndex(5, 6, forward = true, repeat = RepeatMode.Off))
    }

    @Test
    fun `previous on the first song wraps only when repeating`() {
        assertEquals(5, dormantTargetIndex(0, 6, forward = false, repeat = RepeatMode.All))
        assertNull(dormantTargetIndex(0, 6, forward = false, repeat = RepeatMode.Off))
    }

    @Test
    fun `repeat one still moves to the neighbouring song`() {
        assertEquals(4, dormantTargetIndex(3, 6, forward = true, repeat = RepeatMode.One))
        assertEquals(0, dormantTargetIndex(5, 6, forward = true, repeat = RepeatMode.One))
    }

    @Test
    fun `an empty queue has no target`() {
        assertNull(dormantTargetIndex(0, 0, forward = true, repeat = RepeatMode.All))
    }
}
