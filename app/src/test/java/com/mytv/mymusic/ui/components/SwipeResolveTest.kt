package com.mytv.mymusic.ui.components

import com.mytv.mymusic.data.DOWNLOAD_ACTIVE
import com.mytv.mymusic.data.DOWNLOAD_DONE
import com.mytv.mymusic.data.DOWNLOAD_FAILED
import com.mytv.mymusic.data.SwipeAction
import com.mytv.mymusic.data.SwipePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeResolveTest {
    private val defaults = SwipePrefs()
    private fun List<ResolvedAction>.actions() = map { it.action }

    @Test
    fun `favorites page keeps only unfavorite on the end side`() {
        val facts = SwipeFacts(favorite = true, delete = SwipeDeleteFacts("取消收藏", sameAsUnfavorite = true))
        val resolved = resolveSwipe(defaults, facts)
        assertEquals(listOf(SwipeAction.Unfavorite), resolved.end.actions())
        // 用户把取消收藏挪走、只留删除时，删除的字就是「取消收藏」
        val onlyDelete = resolveSwipe(defaults.copy(end = listOf(SwipeAction.Delete)), facts)
        assertEquals(listOf("取消收藏"), onlyDelete.end.map { it.label })
    }

    @Test
    fun `history row that is not a favorite shows only delete`() {
        val resolved = resolveSwipe(defaults, SwipeFacts(favorite = false, delete = SwipeDeleteFacts("删除")))
        assertEquals(listOf(SwipeAction.Delete), resolved.end.actions())
        assertEquals("删除", resolved.end.single().label)
    }

    @Test
    fun `nothing to delete and not a favorite leaves the end side empty`() {
        val resolved = resolveSwipe(defaults, SwipeFacts())
        assertTrue(resolved.end.isEmpty())
        assertNull(resolved.endFull)
        assertEquals(listOf(SwipeAction.Download, SwipeAction.AddToPlaylist), resolved.start.actions())
    }

    @Test
    fun `local songs and live radio get no download`() {
        assertEquals(listOf(SwipeAction.AddToPlaylist), resolveSwipe(defaults, SwipeFacts(local = true)).start.actions())
        assertEquals(listOf(SwipeAction.AddToPlaylist), resolveSwipe(defaults, SwipeFacts(live = true)).start.actions())
    }

    @Test
    fun `downloaded and downloading show a disabled button`() {
        val done = resolveSwipe(defaults, SwipeFacts(download = DOWNLOAD_DONE)).start.first()
        assertEquals(SwipeAction.Download, done.action)
        assertFalse(done.enabled)
        assertEquals("已下载", done.label)
        val active = resolveSwipe(defaults, SwipeFacts(download = DOWNLOAD_ACTIVE)).start.first()
        assertFalse(active.enabled)
        assertEquals("下载中", active.label)
        // 失败的可以重下
        assertTrue(resolveSwipe(defaults, SwipeFacts(download = DOWNLOAD_FAILED)).start.first().enabled)
    }

    @Test
    fun `full favorite on a song already in favorites still resolves`() {
        val full = resolveSwipe(defaults, SwipeFacts(favorite = true)).startFull
        assertEquals(SwipeAction.Favorite, full?.action)
        assertTrue(full!!.enabled)
    }

    @Test
    fun `full download on a downloaded song has no full stop`() {
        val prefs = defaults.copy(startFull = SwipeAction.Download)
        assertNull(resolveSwipe(prefs, SwipeFacts(download = DOWNLOAD_DONE)).startFull)
        assertEquals(SwipeAction.Download, resolveSwipe(prefs, SwipeFacts()).startFull?.action)
    }

    @Test
    fun `full set to do nothing resolves to null`() {
        assertNull(resolveSwipe(defaults, SwipeFacts(delete = SwipeDeleteFacts("删除"))).endFull)
    }

    @Test
    fun `artist and album hide when missing, both hide for live radio`() {
        val prefs = defaults.copy(start = listOf(SwipeAction.OpenArtist, SwipeAction.OpenAlbum))
        assertEquals(listOf(SwipeAction.OpenAlbum), resolveSwipe(prefs, SwipeFacts(hasArtist = false)).start.actions())
        assertEquals(listOf(SwipeAction.OpenArtist), resolveSwipe(prefs, SwipeFacts(hasAlbum = false)).start.actions())
        assertTrue(resolveSwipe(prefs, SwipeFacts(live = true)).start.isEmpty())
    }

    @Test
    fun `switched off resolves to nothing at all`() {
        val resolved = resolveSwipe(defaults.copy(enabled = false), SwipeFacts(favorite = true, delete = SwipeDeleteFacts("删除")))
        assertEquals(ResolvedSwipe.Empty, resolved)
    }

    @Test
    fun `artist to open skips the artist whose page this is`() {
        assertEquals("林间", swipeArtistToOpen("林间", ""))
        assertNull(swipeArtistToOpen("林间", "林间"))
        assertEquals("示例歌手", swipeArtistToOpen("林间/示例歌手", "林间"))
        assertNull(swipeArtistToOpen("", ""))
    }
}
