package com.mytv.mymusic.ui

import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.data.Song

internal data class PlayNextPick(val track: CanonicalTrack, val queueIndex: Int?)

internal fun playNextPicks(
    requested: List<CanonicalTrack>,
    queue: List<Song>,
    tracks: List<CanonicalTrack>,
): List<PlayNextPick> {
    val byId = HashMap<String, Int>()
    val byKey = HashMap<String, Int>()
    queue.forEachIndexed { index, song -> byKey.putIfAbsent(song.key, index) }
    tracks.forEachIndexed { index, track ->
        byId.putIfAbsent(track.id, index)
        track.sources.forEach { byKey.putIfAbsent(it.song.key, index) }
    }
    val seenIds = HashSet<String>()
    val seenKeys = HashSet<String>()
    return requested.mapNotNull { track ->
        val index = byId[track.id] ?: track.sources.firstNotNullOfOrNull { byKey[it.song.key] }
        val picked = index?.let(tracks::get) ?: track
        val keys = picked.sources.map { it.song.key }
        if (track.id in seenIds || picked.id in seenIds || keys.any { it in seenKeys }) return@mapNotNull null
        seenIds += track.id
        seenIds += picked.id
        seenKeys += keys
        PlayNextPick(picked, index)
    }
}

internal sealed interface PlayNextEdit {
    data class Move(val from: Int, val to: Int) : PlayNextEdit
    data class Insert(val at: Int, val tracks: List<CanonicalTrack>) : PlayNextEdit
}

/**
 * 一次「下一首播放」最多安排的首数。排在当前歌后面的歌再多也听不完，
 * 而已在队列里的歌要逐首挪动，全选上千首时会连续发上千次播放器指令卡住主线程。
 */
internal const val PLAY_NEXT_LIMIT = 200

/** 按上限截断，同时返回被丢掉的数量，供提示用户「其余未加入」。 */
internal fun capPlayNext(picks: List<PlayNextPick>): Pair<List<PlayNextPick>, Int> =
    if (picks.size <= PLAY_NEXT_LIMIT) picks to 0
    else picks.take(PLAY_NEXT_LIMIT) to picks.size - PLAY_NEXT_LIMIT

internal fun playNextEdits(
    picks: List<PlayNextPick>,
    size: Int,
    current: Int,
    shuffle: Boolean,
): List<PlayNextEdit> {
    if (current !in 0 until size) return emptyList()
    val upcoming = picks.filterNot { it.queueIndex == current }
    if (!shuffle && upcoming.withIndex().all { (offset, pick) -> pick.queueIndex == current + offset + 1 }) return emptyList()

    val order = (0 until size).toMutableList()
    val pending = ArrayDeque<CanonicalTrack>()
    var anchor = current
    return buildList {
        fun insertPending() {
            if (pending.isEmpty()) return
            val at = queueInsertNextAt(anchor, order.size)
            add(PlayNextEdit.Insert(at, pending.toList()))
            order.addAll(at, List(pending.size) { -1 })
            pending.clear()
        }

        // 从批尾往前插；随机模式下一次只插一首，避免播放器把整批打散。
        for (pick in upcoming.asReversed()) {
            val index = pick.queueIndex
            if (index == null) {
                pending.addFirst(pick.track)
                if (shuffle) insertPending()
                continue
            }
            insertPending()
            val from = order.indexOf(index)
            val to = queueMoveNextTarget(from, anchor, order.size)
            if (to != null) {
                add(PlayNextEdit.Move(from, to))
                order.add(to, order.removeAt(from))
                if (from < anchor) anchor--
            } else if (shuffle && from == anchor + 1) {
                // 列表相邻不等于随机顺序相邻；挪回原位才会将它接到当前歌曲的随机顺序后面。
                add(PlayNextEdit.Move(from, anchor))
                add(PlayNextEdit.Move(anchor, from))
            }
        }
        insertPending()
    }
}
