package com.mytv.mymusic.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanDiffTest {
    private fun file(
        uri: String,
        size: Long = 4_000_000,
        modifiedAt: Long = 1_000,
        origin: String = LOCAL_ORIGIN_MEDIA,
        lrc: String = "",
    ) = ScannedFile(
        uri = uri,
        origin = origin,
        displayName = uri.substringAfterLast('/') + ".mp3",
        relativePath = "Music/",
        size = size,
        modifiedAt = modifiedAt,
        mimeType = "audio/mpeg",
        title = "晴天",
        artist = "周杰伦",
        durationMs = 269_000,
        lrcUri = lrc,
    )

    private fun row(file: ScannedFile, id: String) = LocalScanDiff.merge(null, file, id, now = 10)

    @Test
    fun unchangedSizeAndTimeAreNotHashedAgain() {
        val a = file("content://media/1")
        val b = file("content://media/2")
        val existing = listOf(row(a, "sha256:a"), row(b, "sha256:b"))
        val plan = LocalScanDiff.plan(existing, listOf(a, b.copy(modifiedAt = 2_000), file("content://media/3")), now = 20)
        assertEquals(listOf("sha256:a"), plan.unchanged.map { it.localId })
        assertSame("没变的记录原样沿用，不触发写库", existing[0], plan.unchanged[0])
        assertEquals(listOf("content://media/2", "content://media/3"), plan.toHash.map { it.uri })
    }

    @Test
    fun sizeChangeIsRehashed() {
        val a = file("content://media/1")
        val plan = LocalScanDiff.plan(listOf(row(a, "sha256:a")), listOf(a.copy(size = a.size + 1)), now = 20)
        assertTrue(plan.unchanged.isEmpty())
        assertEquals(1, plan.toHash.size)
    }

    @Test
    fun missingRowComesBackAndLrcIsRefreshed() {
        val a = file("content://media/1")
        val gone = row(a, "sha256:a").copy(missing = true)
        val plan = LocalScanDiff.plan(listOf(gone), listOf(a.copy(lrcUri = "content://tree/x.lrc")), now = 30)
        val back = plan.unchanged.single()
        assertFalse(back.missing)
        assertEquals("content://tree/x.lrc", back.lrcUri)
        assertEquals(30, back.updatedAt)
    }

    @Test
    fun liveRowWinsOverMissingRowWithSameUri() {
        val a = file("content://media/1")
        val old = row(a, "sha256:old").copy(missing = true, size = 1)
        val live = row(a, "sha256:new")
        val plan = LocalScanDiff.plan(listOf(old, live), listOf(a), now = 20)
        assertEquals("sha256:new", plan.unchanged.single().localId)
    }

    @Test
    fun movedFileKeepsMatchAndCover() {
        val a = file("content://media/1")
        val matched = row(a, "sha256:a").copy(
            matchProvider = "netease",
            matchId = "186016",
            matchMethod = LocalMatchMethod.TAG,
            matchConfidence = 0.93f,
            coverPath = "file:///covers/a.jpg",
            tagsRead = true,
        )
        val moved = file("content://tree/doc/Music%2Fa", origin = "content://tree/root")
        val merged = LocalScanDiff.merge(matched, moved, "sha256:a", now = 40)
        assertEquals("content://tree/doc/Music%2Fa", merged.uri)
        assertEquals("content://tree/root", merged.origin)
        assertEquals("186016", merged.matchId)
        assertEquals("file:///covers/a.jpg", merged.coverPath)
        assertTrue(merged.tagsRead)
        assertFalse(merged.missing)
    }

    @Test
    fun newIdentityStartsUnreadAndUnmatched() {
        val fresh = row(file("content://media/9"), "sha256:z")
        assertFalse(fresh.tagsRead)
        assertFalse(fresh.matched)
        assertEquals(269, fresh.durationSec)
        assertEquals("local:sha256:z", fresh.key)
    }

    @Test
    fun vanishedOnlyCountsOriginsThatWereScanned() {
        val media = row(file("content://media/1"), "sha256:m")
        val folder = row(file("content://tree/a", origin = "tree:usb"), "sha256:u")
        val alreadyGone = row(file("content://media/2"), "sha256:g").copy(missing = true)
        val existing = listOf(media, folder, alreadyGone)
        // U 盘这次没读成：它的歌不能被标成“不在了”
        assertEquals(listOf("sha256:m"), LocalScanDiff.vanished(existing, emptySet(), setOf(LOCAL_ORIGIN_MEDIA)))
        assertTrue(LocalScanDiff.vanished(existing, setOf("sha256:m"), setOf(LOCAL_ORIGIN_MEDIA)).isEmpty())
        assertEquals(
            listOf("sha256:m", "sha256:u"),
            LocalScanDiff.vanished(existing, emptySet(), setOf(LOCAL_ORIGIN_MEDIA, "tree:usb")),
        )
    }
}
