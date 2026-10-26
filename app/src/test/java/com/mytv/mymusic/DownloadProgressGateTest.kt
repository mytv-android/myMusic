package com.mytv.mymusic

import com.mytv.mymusic.data.update.DownloadProgressGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressGateTest {
    @Test
    fun emitsFirstAndThrottlesIntermediateChunks() {
        val gate = DownloadProgressGate(intervalNanos = 100)
        assertTrue(gate.shouldEmit(0, complete = false))
        assertFalse(gate.shouldEmit(1, complete = false))
        assertFalse(gate.shouldEmit(99, complete = false))
        assertTrue(gate.shouldEmit(100, complete = false))
        assertFalse(gate.shouldEmit(199, complete = false))
        assertTrue(gate.shouldEmit(200, complete = false))
    }

    @Test
    fun completionIsNotLostInsideThrottleWindow() {
        val gate = DownloadProgressGate(intervalNanos = 100)
        assertTrue(gate.shouldEmit(10, complete = false))
        assertTrue(gate.shouldEmit(11, complete = true))
    }

    @Test
    fun newDownloadDoesNotInheritPreviousThrottle() {
        assertTrue(DownloadProgressGate().shouldEmit(1, complete = false))
        assertTrue(DownloadProgressGate().shouldEmit(2, complete = false))
    }
}
