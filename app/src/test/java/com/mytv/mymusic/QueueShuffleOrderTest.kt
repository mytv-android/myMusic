package com.mytv.mymusic

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import com.mytv.mymusic.playback.QueueShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

class QueueShuffleOrderTest {
    private fun ShuffleOrder.order() = (this as QueueShuffleOrder).toList()

    private fun assertPermutation(order: ShuffleOrder) = assertEquals((0 until order.length).toList(), order.order().sorted())

    private fun start(length: Int, startIndex: Int, seed: Int = 7) = QueueShuffleOrder(Random(seed)).cloneAndSet(length, startIndex)

    @Test
    fun `a new queue starts at the chosen song and shuffles the rest`() {
        val order = start(20, 5)
        assertEquals(5, order.firstIndex)
        assertPermutation(order)
        assertEquals(order.order().last(), order.lastIndex)
        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(5))
        assertEquals(order.order()[1], order.getNextIndex(5))
        assertEquals(C.INDEX_UNSET, order.getNextIndex(order.lastIndex))
    }

    @Test
    fun `an empty order has no first or last song`() {
        val order = QueueShuffleOrder()
        assertEquals(0, order.length)
        assertEquals(C.INDEX_UNSET, order.firstIndex)
        assertEquals(C.INDEX_UNSET, order.lastIndex)
    }

    @Test
    fun `a song inserted after the current one plays next`() {
        val before = start(10, 3)
        val after = before.cloneAndInsert(4, 1)
        assertPermutation(after)
        assertEquals(4, after.getNextIndex(3))
        // 其余先后不变，只是第 4 首及以后的下标都加一
        assertEquals(before.order().map { if (it >= 4) it + 1 else it }, after.order().filter { it != 4 })
    }

    @Test
    fun `a song inserted at the front goes first`() {
        val after = start(5, 2).cloneAndInsert(0, 1)
        assertEquals(0, after.firstIndex)
        assertPermutation(after)
    }

    @Test
    fun `replacing the current song keeps its place and is what plays after the removal`() {
        val before = start(12, 0)
        // 播放器换地址：先在第 6 首后面插新的，再删掉旧的第 6 首
        val inserted = before.cloneAndInsert(7, 1)
        assertEquals(7, inserted.getNextIndex(6))
        val replaced = inserted.cloneAndRemove(6, 7)
        assertEquals(before.order(), replaced.order())
    }

    @Test
    fun `moving a later song to right after the current one makes it next`() {
        val before = start(10, 0)
        val after = before.cloneAndMove(6, 7, 1)
        assertPermutation(after)
        assertEquals(1, after.getNextIndex(0))
    }

    @Test
    fun `moving an earlier song past the current one follows the shifted current`() {
        // 队列 0..5 正在放第 3 首；把第 0 首挪到它后面：挪完正在放的是第 2 首，挪过来的是第 3 首
        val before = start(6, 3)
        val after = before.cloneAndMove(0, 1, 3)
        assertPermutation(after)
        assertEquals(3, after.getNextIndex(2))
        val newIndex = intArrayOf(3, 0, 1, 2, 4, 5)
        assertEquals(before.order().filter { it != 0 }.map { newIndex[it] }, after.order().filter { it != 3 })
    }

    @Test
    fun `moving several songs keeps the play order of every song`() {
        val before = start(8, 0)
        val after = before.cloneAndMove(1, 3, 5)
        // 第 1、2 首挪到第 5、6 位，第 3–6 首往前两位
        val newIndex = intArrayOf(0, 5, 6, 1, 2, 3, 4, 7)
        assertEquals(before.order().map { newIndex[it] }, after.order())
    }

    @Test
    fun `a move onto itself changes nothing`() {
        val before = start(5, 0)
        assertSame(before, before.cloneAndMove(2, 3, 2))
    }

    @Test
    fun `removing songs keeps the rest in order`() {
        val before = start(10, 4)
        val after = before.cloneAndRemove(2, 5)
        assertPermutation(after)
        assertEquals(before.order().filter { it !in 2 until 5 }.map { if (it >= 5) it - 3 else it }, after.order())
    }

    @Test
    fun `inserting several songs scatters them and keeps the rest in order`() {
        val before = start(6, 0)
        val after = before.cloneAndInsert(6, 4)
        assertEquals(10, after.length)
        assertPermutation(after)
        assertEquals(before.order(), after.order().filter { it < 6 })
    }

    @Test
    fun `clearing empties the order`() {
        assertEquals(0, start(5, 1).cloneAndClear().length)
    }
}
