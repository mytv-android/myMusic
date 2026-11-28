/*
 * AMLL (Apple Music-like Lyrics), https://github.com/amll-dev/applemusic-like-lyrics
 * Copyright (C) the AMLL contributors (amll-dev)
 * Modified for myMusic on 2026-11-28 (Kotlin / Jetpack Compose rewrite); the changes are listed below.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 2026-11-28 myMusic 改写：取自 AMLL core 0.7.2（@4d8e0270）的 lyric-player/base/timeline.ts（TimelineController）
// 和 lyric-player/base/focus.ts（FocusController）。正在唱的集合、亮着的集合（新句开始才熄掉唱完的句子）、焦点句、
// 跳转（倒退或调用方标明）时的重建、间奏焦点照原样；改动：时间用毫秒的 Double，底栏（歌曲信息行）不搬，焦点不会落到底栏。
// 跳转判定另见 [amllDetectSeek]：原版的 SeekDetector 按物理时钟比对，这里按舞台时钟和帧间隔比对。
package com.mytv.mymusic.ui.player.stage.modes

import kotlin.math.max
import kotlin.math.min

/**
 * 时间线：给定每组的起止时间，按播放时间推出正在唱（playing）和亮着（highlighted）的组。
 * 正常播放时一组唱完不马上灭，等有新的组开始唱才一起灭；焦点（[scrollToIndex]）是亮着的组里最靠前的那组。
 * 每次 [sync] 之后，added / removed 几个表和 changed 几个标志是这一次相对上一次的差别。
 */
internal class AmllTimeline {
    private var starts = DoubleArray(0)
    private var ends = DoubleArray(0)
    private var maxEnd = 0.0
    private var interludes: List<AmllInterlude> = emptyList()
    private var playbackCursor = 0
    private var interludeCursor = 0
    private val nextPlaying = LinkedHashSet<Int>()
    private val nextHighlighted = LinkedHashSet<Int>()
    private val expired = ArrayList<Int>()

    val playing = LinkedHashSet<Int>()
    val highlighted = LinkedHashSet<Int>()

    var currentTime = 0.0
        private set
    var scrollToIndex = 0
        private set

    /** 亮着的组里最靠后的那组，没有亮着的组时是 −1。 */
    var latestHighlighted = -1
        private set
    var endOfSong = false
        private set
    var activeInterlude: AmllInterlude? = null
        private set

    /** 处在间奏里、又没有亮着的组：焦点给间奏三点。 */
    var focusOnInterlude = false
        private set

    val addedPlaying = ArrayList<Int>()
    val removedPlaying = ArrayList<Int>()
    val addedHighlighted = ArrayList<Int>()
    val removedHighlighted = ArrayList<Int>()
    var hasChanged = false
        private set
    var timeJumped = false
        private set
    var interludeChanged = false
        private set
    var scrollToChanged = false
        private set
    var endOfSongChanged = false
        private set

    val size: Int get() = starts.size

    fun setBounds(starts: DoubleArray, ends: DoubleArray, interludes: List<AmllInterlude>) {
        this.starts = starts
        this.ends = ends
        this.interludes = interludes
        maxEnd = ends.fold(0.0) { a, b -> max(a, b) }
        reset()
    }

    /** 推进到 [time]（毫秒）。[forceSeek] 是调用方认定的跳转；时间倒退也按跳转处理。 */
    fun sync(time: Double, forceSeek: Boolean = false) {
        addedPlaying.clear()
        removedPlaying.clear()
        addedHighlighted.clear()
        removedHighlighted.clear()
        val prevInterlude = activeInterlude
        val prevFocusOnInterlude = focusOnInterlude
        val prevScrollTo = scrollToIndex
        val prevEndOfSong = endOfSong

        val jump = forceSeek || time < currentTime
        val active = resolveActiveInterlude(time, jump)
        activeInterlude = active
        val pastLastLine = starts.isNotEmpty() && time >= maxEnd
        if (jump) performSeek(time, active != null || pastLastLine) else performPlayback(time)
        if (active != null && playing.isEmpty()) flushAllHighlighted()
        if (pastLastLine) flushAllHighlighted()
        focusOnInterlude = active != null && highlighted.isEmpty()

        interludeChanged = prevInterlude !== active
        val focusChanged = prevFocusOnInterlude != focusOnInterlude
        scrollToChanged = prevScrollTo != scrollToIndex
        endOfSongChanged = pastLastLine != prevEndOfSong
        hasChanged = jump || addedPlaying.isNotEmpty() || removedPlaying.isNotEmpty() ||
            addedHighlighted.isNotEmpty() || removedHighlighted.isNotEmpty() ||
            interludeChanged || focusChanged || scrollToChanged || endOfSongChanged
        currentTime = time
        latestHighlighted = highlighted.maxOrNull() ?: -1
        endOfSong = pastLastLine
        timeJumped = jump
    }

    private fun performPlayback(time: Double) {
        val iterator = playing.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id !in starts.indices || time < starts[id] || ends[id] <= time) {
                iterator.remove()
                removedPlaying += id
            }
        }
        var cursor = max(0, playbackCursor)
        while (cursor < starts.size) {
            if (starts[cursor] > time) break
            if (starts[cursor] <= time && ends[cursor] > time && cursor !in playing) {
                playing += cursor
                addedPlaying += cursor
            }
            cursor++
        }
        playbackCursor = cursor

        expired.clear()
        for (id in highlighted) if (id !in playing) expired += id
        for (id in addedPlaying) {
            highlighted += id
            addedHighlighted += id
        }
        if (addedPlaying.isNotEmpty()) {
            for (id in expired) {
                highlighted -= id
                removedHighlighted += id
            }
            scrollToIndex = highlighted.minOrNull() ?: Int.MAX_VALUE
        }
    }

    private fun performSeek(time: Double, dropLingeringWhenIdle: Boolean) {
        nextPlaying.clear()
        nextHighlighted.clear()
        var left = 0
        var right = starts.size - 1
        var firstGreater = starts.size
        while (left <= right) {
            val mid = (left + right) ushr 1
            if (starts[mid] > time) {
                firstGreater = mid
                right = mid - 1
            } else {
                left = mid + 1
            }
        }
        var anchor = -1
        for (i in firstGreater - 1 downTo 0) {
            if (ends[i] > starts[i]) {
                anchor = i
                break
            }
        }
        if (anchor == -1) {
            playbackCursor = firstGreater
            scrollToIndex = 0
            commitSeekDiff()
            return
        }
        val anchorStart = starts[anchor]
        var minPlaying = -1
        var minHighlighted = -1
        for (i in anchor downTo 0) {
            if (ends[i] <= anchorStart) continue
            if (starts[i] <= time && ends[i] > time) {
                nextPlaying += i
                minPlaying = i
            }
            nextHighlighted += i
            minHighlighted = i
        }
        if (dropLingeringWhenIdle && nextPlaying.isEmpty()) nextHighlighted.clear()
        playbackCursor = if (minPlaying == -1) firstGreater else minPlaying
        scrollToIndex = minHighlighted
        commitSeekDiff()
    }

    private fun commitSeekDiff() {
        val playingIt = playing.iterator()
        while (playingIt.hasNext()) {
            val id = playingIt.next()
            if (id !in nextPlaying) {
                playingIt.remove()
                removedPlaying += id
            }
        }
        for (id in nextPlaying) if (playing.add(id)) addedPlaying += id
        val highlightedIt = highlighted.iterator()
        while (highlightedIt.hasNext()) {
            val id = highlightedIt.next()
            if (id !in nextHighlighted) {
                highlightedIt.remove()
                removedHighlighted += id
            }
        }
        for (id in nextHighlighted) if (highlighted.add(id)) addedHighlighted += id
    }

    private fun flushAllHighlighted() {
        if (highlighted.isEmpty()) return
        removedHighlighted += highlighted
        highlighted.clear()
    }

    private fun resolveActiveInterlude(time: Double, seek: Boolean): AmllInterlude? {
        if (interludes.isEmpty()) return null
        if (seek) {
            var cursor = interludes.size
            var left = 0
            var right = interludes.size - 1
            while (left <= right) {
                val mid = (left + right) ushr 1
                if (interludes[mid].endMs > time) {
                    cursor = mid
                    right = mid - 1
                } else {
                    left = mid + 1
                }
            }
            interludeCursor = cursor
            if (cursor < interludes.size) {
                val inter = interludes[cursor]
                if (time >= inter.startMs && time < inter.endMs) return inter
            }
            return null
        }
        while (interludeCursor < interludes.size) {
            val inter = interludes[interludeCursor]
            if (time >= inter.startMs && time < inter.endMs) return inter
            if (time >= inter.endMs) interludeCursor++ else break
        }
        return null
    }

    private fun reset() {
        playbackCursor = 0
        interludeCursor = 0
        playing.clear()
        highlighted.clear()
        nextPlaying.clear()
        nextHighlighted.clear()
        currentTime = 0.0
        scrollToIndex = 0
        latestHighlighted = -1
        endOfSong = false
        activeInterlude = null
        focusOnInterlude = false
    }
}

/**
 * 焦点（FocusController）：这一帧排版对齐谁。自动对齐被手势挂起时，焦点冻在挂起前的那一行或那段间奏上
 * （间奏结束了就落到它后面那句）；否则间奏焦点给三点、播完给最后一句，其余给 [AmllTimeline.scrollToIndex]。
 */
internal class AmllFocus {
    var kind = AMLL_FOCUS_LINE
        private set

    /** [kind] 是句子时是组的下标，是间奏时是间奏挂的那组（−1 是第一句之前）。 */
    var index = 0
        private set

    fun resolve(timeline: AmllTimeline, lineCount: Int, suspended: Boolean, canDisplayInterlude: Boolean) {
        if (suspended) {
            if (kind == AMLL_FOCUS_INTERLUDE) {
                val live = timeline.focusOnInterlude && timeline.activeInterlude != null && canDisplayInterlude
                if (!live) set(AMLL_FOCUS_LINE, clampLine(index + 1, lineCount))
            } else {
                set(AMLL_FOCUS_LINE, clampLine(index, lineCount))
            }
            return
        }
        val interlude = timeline.activeInterlude
        when {
            timeline.focusOnInterlude && interlude != null ->
                if (canDisplayInterlude) set(AMLL_FOCUS_INTERLUDE, interlude.anchor) else set(AMLL_FOCUS_LINE, clampLine(interlude.anchor + 1, lineCount))
            timeline.endOfSong -> set(AMLL_FOCUS_LINE, clampLine(lineCount - 1, lineCount))
            else -> set(AMLL_FOCUS_LINE, clampLine(timeline.scrollToIndex, lineCount))
        }
    }

    fun reset() = set(AMLL_FOCUS_LINE, 0)

    private fun set(kind: Int, index: Int) {
        this.kind = kind
        this.index = index
    }

    private fun clampLine(index: Int, lineCount: Int): Int = if (lineCount <= 0) 0 else min(max(0, index), lineCount - 1)
}

/**
 * 这一帧的歌词时间算不算跳转：倒退超过 30ms，或者比上一帧多走了帧间隔再加 [AMLL_SEEK_SLACK_MS] 以上（暂停时帧间隔按 0 算）。
 * 宿主时钟每 100ms 校准一次、外推时偶尔超前一点，所以留了余量。
 */
internal fun amllDetectSeek(lastMs: Double, ms: Double, elapsedMs: Double, playing: Boolean): Boolean {
    if (lastMs.isNaN()) return false
    val expected = if (playing) elapsedMs else 0.0
    return ms < lastMs - 30.0 || ms - lastMs > expected + AMLL_SEEK_SLACK_MS
}

internal const val AMLL_FOCUS_LINE = 0
internal const val AMLL_FOCUS_INTERLUDE = 1
internal const val AMLL_SEEK_SLACK_MS = 600.0
