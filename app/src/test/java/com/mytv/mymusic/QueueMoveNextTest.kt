package com.mytv.mymusic

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.ui.LISTENING_FEED_MARK
import com.mytv.mymusic.ui.ListeningFeedUi
import com.mytv.mymusic.ui.moved
import com.mytv.mymusic.ui.queueMoveNextTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMoveNextTest {
    private val queue = listOf("a", "b", "c", "d", "e", "f")

    /** 照着播放器的做法挪一次，返回新队列和当前这首的新位置。 */
    private fun moveNext(from: Int, current: Int): Pair<List<String>, Int>? {
        val target = queueMoveNextTarget(from, current, queue.size) ?: return null
        val next = queue.moved(from, target)
        return next to next.indexOf(queue[current])
    }

    @Test
    fun laterItemLandsRightAfterCurrent() {
        val (next, current) = moveNext(from = 4, current = 1)!!
        assertEquals(listOf("a", "b", "e", "c", "d", "f"), next)
        assertEquals(1, current)
        assertEquals("e", next[current + 1])
    }

    @Test
    fun earlierItemLandsRightAfterCurrentAndCurrentStepsBack() {
        val (next, current) = moveNext(from = 0, current = 3)!!
        assertEquals(listOf("b", "c", "d", "a", "e", "f"), next)
        assertEquals(2, current)
        assertEquals("a", next[current + 1])
    }

    @Test
    fun lastItemAndCurrentAtTheEnd() {
        assertEquals(listOf("a", "f", "b", "c", "d", "e"), moveNext(from = 5, current = 0)!!.first)
        val (next, current) = moveNext(from = 2, current = 5)!!
        assertEquals(listOf("a", "b", "d", "e", "f", "c"), next)
        assertEquals(4, current)
    }

    @Test
    fun currentOrAlreadyNextOrOutOfRangeIsANoOp() {
        assertNull(queueMoveNextTarget(2, 2, queue.size))
        assertNull(queueMoveNextTarget(3, 2, queue.size))
        assertNull(queueMoveNextTarget(-1, 2, queue.size))
        assertNull(queueMoveNextTarget(6, 2, queue.size))
        assertNull(queueMoveNextTarget(1, -1, queue.size))
        assertNull(queueMoveNextTarget(0, 0, 0))
    }

    @Test
    fun feedOwnsOnlySongsCarryingItsMark() {
        val feed = ListeningFeedUi("fm", "私人漫游", "netease", "网易云音乐", appending = false)
        assertTrue(feed.owns(Song(id = "1", extra = mapOf(LISTENING_FEED_MARK to "fm"))))
        assertFalse(feed.owns(Song(id = "2", extra = mapOf(LISTENING_FEED_MARK to "other"))))
        assertFalse(feed.owns(Song(id = "3")))
        assertFalse(feed.owns(null))
    }
}
