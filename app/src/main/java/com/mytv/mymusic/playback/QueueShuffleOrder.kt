package com.mytv.mymusic.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * 跟着队列改动走的随机顺序。播放器自带的随机顺序只按位置记：挪动一首它不跟着走，新插的随手一放；
 * 换地址（先在后面插新的、再删掉旧的）时正在放的这首被删，播放器就沿着随机顺序跳到一首不相干的歌上。这里改成：
 * - 换一整个队列：起播的那首排第一，其余打乱；
 * - 插入或挪动单独一首：排到它在队列里前一首的紧后面。“下一首播放”就是挪 / 插到正在放的这首后面，
 *   随机时也照样下一首放它；换地址时新的那首顶上旧的那一格；
 * - 一次插入多首：各自随机放；挪动多首、删除：其余先后不变。
 */
@UnstableApi
class QueueShuffleOrder private constructor(
    private val shuffled: IntArray,
    private val random: Random,
) : ShuffleOrder {
    private val indexInShuffled = IntArray(shuffled.size).also { at -> shuffled.forEachIndexed { i, index -> at[index] = i } }

    constructor(random: Random = Random.Default) : this(IntArray(0), random)

    override fun getLength() = shuffled.size

    override fun getNextIndex(index: Int) = shuffled.getOrElse(indexInShuffled[index] + 1) { C.INDEX_UNSET }

    override fun getPreviousIndex(index: Int) = shuffled.getOrElse(indexInShuffled[index] - 1) { C.INDEX_UNSET }

    override fun getLastIndex() = shuffled.lastOrNull() ?: C.INDEX_UNSET

    override fun getFirstIndex() = shuffled.firstOrNull() ?: C.INDEX_UNSET

    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder {
        val order = (0 until insertionCount).filter { it != startIndex }.shuffled(random).toMutableList()
        if (startIndex in 0 until insertionCount) order.add(0, startIndex)
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (shuffled.isEmpty()) return cloneAndSet(insertionCount, C.INDEX_UNSET)
        val order = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        if (insertionCount == 1) {
            order.placeAfter(insertionIndex - 1, insertionIndex)
        } else {
            for (i in 0 until insertionCount) order.add(random.nextInt(order.size + 1), insertionIndex + i)
        }
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        if (count <= 0 || indexFrom == newIndexFrom) return this
        // 队列挪完后每一首的新下标（同 Player.moveMediaItems：先取出这几首，再从 newIndexFrom 放回去）
        val queue = MutableList(shuffled.size) { it }
        val moved = queue.subList(indexFrom, indexToExclusive).toList()
        queue.subList(indexFrom, indexToExclusive).clear()
        queue.addAll(newIndexFrom, moved)
        val newIndex = IntArray(queue.size).also { at -> queue.forEachIndexed { i, old -> at[old] = i } }
        val order = shuffled.map { newIndex[it] }.toMutableList()
        if (count == 1) {
            order.removeAt(order.indexOf(newIndexFrom))
            order.placeAfter(newIndexFrom - 1, newIndexFrom)
        }
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val order = shuffled.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - count else it }
        return QueueShuffleOrder(order.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)

    /** 按随机顺序排好的队列下标，测试用。 */
    internal fun toList(): List<Int> = shuffled.toList()

    /** 把 [index] 插到 [after] 的紧后面；[after] 不在（队列第一首之前）就排第一。 */
    private fun MutableList<Int>.placeAfter(after: Int, index: Int) = add(indexOf(after) + 1, index)
}
