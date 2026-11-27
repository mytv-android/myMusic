package com.mytv.mymusic

import com.mytv.mymusic.aggregate.CanonicalTrack
import com.mytv.mymusic.aggregate.TrackMatcher
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.playback.QueueShuffleOrder
import com.mytv.mymusic.ui.PlayNextEdit
import com.mytv.mymusic.ui.PLAY_NEXT_LIMIT
import com.mytv.mymusic.ui.capPlayNext
import com.mytv.mymusic.ui.playNextEdits
import com.mytv.mymusic.ui.playNextPicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QueuePlayNextTest {
    @Test
    fun `last operation comes first, without replaying current or duplicating songs`() {
        val queue = QueueFixture(listOf("A", "D", "B"), current = "A")
        queue.schedule("B")
        queue.schedule("C")
        queue.schedule("C", "A")
        assertEquals(listOf("A", "C", "B", "D"), queue.visible)
    }

    @Test
    fun `a mixed selection keeps its order in both the list and shuffled playback`() {
        val queue = QueueFixture(
            listOf("P", "A", "B", "T", "X"),
            current = "A",
            shuffled = listOf("A", "X", "P", "T", "B"),
        )
        queue.schedule("T", "N1", "P", "N2", "A", "T")
        assertEquals(listOf("A", "T", "N1", "P", "N2", "B", "X"), queue.visible)
        assertEquals(listOf("A", "T", "N1", "P", "N2", "X", "B"), queue.playback)
    }

    @Test
    fun `a visibly adjacent song must still become next in shuffle`() {
        val queue = QueueFixture(listOf("A", "B", "C"), current = "A", shuffled = listOf("A", "C", "B"))
        queue.schedule("B")
        assertEquals(listOf("A", "B", "C"), queue.visible)
        assertEquals(listOf("A", "B", "C"), queue.playback)
    }

    @Test
    fun `lookup by song key retains the already queued version and its alternate sources`() {
        val existing = track("B")
        val alternate = track("Other", "different-source")
        val queued = existing.copy(sources = existing.sources + alternate.sources)
        val fresh = track("C")
        val picks = playNextPicks(
            listOf(alternate, existing, fresh),
            listOf(alternate.sources.single().song),
            listOf(queued),
        )
        assertEquals(listOf(0, null), picks.map { it.queueIndex })
        assertSame(queued, picks.first().track)
        assertSame(fresh, picks.last().track)
    }

    @Test
    fun `unshuffled consecutive new selections can be inserted in one call`() {
        val anchor = track("A")
        val selected = (1..200).map { track("B-$it") }
        val picks = playNextPicks(selected, listOf(anchor.sources.single().song), listOf(anchor))
        assertEquals(listOf(PlayNextEdit.Insert(1, selected)), playNextEdits(picks, 1, 0, shuffle = false))
    }

    @Test
    fun `select all is capped so a huge album is not moved song by song`() {
        val anchor = track("A")
        val selected = (1..565).map { track("B-$it") }
        val picks = playNextPicks(selected, listOf(anchor.sources.single().song), listOf(anchor))
        val (capped, skipped) = capPlayNext(picks)
        assertEquals(PLAY_NEXT_LIMIT, capped.size)
        assertEquals(565 - PLAY_NEXT_LIMIT, skipped)
        assertEquals(listOf(PlayNextEdit.Insert(1, selected.take(PLAY_NEXT_LIMIT))), playNextEdits(capped, 1, 0, shuffle = false))
    }

    private class QueueFixture(labels: List<String>, private val current: String, shuffled: List<String>? = null) {
        private val tracks = labels.map { track(it) }.toMutableList()
        private var shuffleOrder = shuffled?.let { QueueShuffleOrder.of(it.map(labels::indexOf).toIntArray()) }
        val visible: List<String> get() = tracks.map { it.id }
        val playback: List<String> get() = shuffleOrder?.toList()?.map { tracks[it].id } ?: visible

        fun schedule(vararg labels: String) {
            val picks = playNextPicks(labels.map { track(it) }, tracks.map { it.sources.first().song }, tracks)
            for (edit in playNextEdits(picks, tracks.size, visible.indexOf(current), shuffleOrder != null)) {
                when (edit) {
                    is PlayNextEdit.Move -> {
                        tracks.add(edit.to, tracks.removeAt(edit.from))
                        shuffleOrder = shuffleOrder?.cloneAndMove(edit.from, edit.from + 1, edit.to) as QueueShuffleOrder?
                    }
                    is PlayNextEdit.Insert -> {
                        tracks.addAll(edit.at, edit.tracks)
                        shuffleOrder = shuffleOrder?.cloneAndInsert(edit.at, edit.tracks.size) as QueueShuffleOrder?
                    }
                }
            }
        }
    }

    private companion object {
        fun track(label: String, provider: String = "test") = TrackMatcher.singleSource(
            Song(id = label, name = label, source = provider), provider,
        ).copy(id = label)
    }
}
