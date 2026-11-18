package com.mytv.mymusic

import androidx.media3.exoplayer.source.ShuffleOrder
import com.mytv.mymusic.playback.QueueShuffleOrder
import com.mytv.mymusic.ui.queueRemovalAllowed
import com.mytv.mymusic.ui.queueRestoreAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueRemoveTest {
    @Test
    fun `the song that is playing cannot be removed`() {
        assertFalse(queueRemovalAllowed(index = 3, current = 3, size = 6))
    }

    @Test
    fun `out of range cannot be removed`() {
        assertFalse(queueRemovalAllowed(index = -1, current = 0, size = 6))
        assertFalse(queueRemovalAllowed(index = 6, current = 0, size = 6))
        assertFalse(queueRemovalAllowed(index = 0, current = 0, size = 0))
    }

    @Test
    fun `any other song can be removed`() {
        assertTrue(queueRemovalAllowed(index = 0, current = 3, size = 6))
        assertTrue(queueRemovalAllowed(index = 5, current = 3, size = 6))
        // 播放器还没有当前项（-1）时随便移
        assertTrue(queueRemovalAllowed(index = 2, current = -1, size = 6))
    }

    @Test
    fun `undo puts it back where it was`() {
        assertEquals(2, queueRestoreAt(index = 2, size = 5))
        assertEquals(5, queueRestoreAt(index = 5, size = 5))
    }

    @Test
    fun `undo after the queue got shorter appends`() {
        assertEquals(3, queueRestoreAt(index = 7, size = 3))
    }

    @Test
    fun `a negative index goes to the front`() {
        assertEquals(0, queueRestoreAt(index = -2, size = 3))
    }

    private fun ShuffleOrder.order() = (this as QueueShuffleOrder).toList()

    @Test
    fun `remove then insert back keeps a full shuffle order`() {
        val before = QueueShuffleOrder(Random(11)).cloneAndSet(12, 4)
        for (i in 0 until 12) {
            val after = before.cloneAndRemove(i, i + 1).cloneAndInsert(queueRestoreAt(i, 11), 1)
            assertEquals((0 until 12).toList(), after.order().sorted())
        }
    }

    @Test
    fun `undoing the song right after the current one makes it play next again`() {
        // 撤销后的随机顺序不保证和原来一样：插回的这首排在它前一首（这里就是正在放的）后面
        val current = 4
        val before = QueueShuffleOrder(Random(3)).cloneAndSet(10, current)
        val removed = before.cloneAndRemove(current + 1, current + 2)
        val restored = removed.cloneAndInsert(queueRestoreAt(current + 1, 9), 1)
        assertEquals((0 until 10).toList(), restored.order().sorted())
        assertEquals(current + 1, restored.getNextIndex(current))
    }
}
