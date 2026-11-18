package com.mytv.mymusic.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洛雪自定义音源随同步文件走：脚本正文能往返、删除的墓碑挡住旧版本、
 * 老文件（没有这一块）照常能读。
 */
class SyncLxSourcesTest {
    private fun source(
        id: String,
        hash: String = "h1",
        enabled: Boolean = true,
        position: Int = 0,
        updatedAt: String = "2026-11-18T10:00:00Z",
        deletedAt: String? = null,
        script: String = "// js",
    ) = SyncLxSource(
        id = id,
        name = "星海音乐源",
        version = "v3",
        scriptHash = hash,
        script = script,
        enabled = enabled,
        position = position,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun snapshot(sources: List<SyncLxSource>) =
        SyncSnapshot(deviceId = "d", updatedAt = "2026-11-18T10:00:00Z", lxSources = sources)

    @Test
    fun scriptRoundTrips() {
        val original = snapshot(listOf(source("lx_a", script = "const x = 1;")))
        val back = SyncJson.decode(SyncJson.encode(original))
        assertEquals("const x = 1;", back.lxSources.single().script)
        assertEquals("h1", back.lxSources.single().scriptHash)
    }

    @Test
    fun oldFileWithoutLxStillDecodes() {
        val raw = """{"schema":"mymusic.library","version":3,"deviceId":"old","updatedAt":"2026-10-01T00:00:00Z",
            "playlists":[],"favorites":[],"cookies":[]}"""
        assertTrue(SyncJson.decode(raw).lxSources.isEmpty())
    }

    @Test
    fun deletedOnOneSideWins() {
        val local = snapshot(listOf(source("lx_a", deletedAt = "2026-11-18T12:00:00Z", script = "")))
        val remote = snapshot(listOf(source("lx_a", updatedAt = "2026-11-17T10:00:00Z")))
        val merged = SyncMerge.merge(null, local, remote)
        assertTrue(merged.lxSources.single().deletedAt != null)
        assertEquals(emptyList<SyncLxSource>(), SyncMerge.activeLxSources(merged.lxSources))
    }

    @Test
    fun newerScriptWins() {
        val local = snapshot(listOf(source("lx_a", hash = "new", updatedAt = "2026-11-18T12:00:00Z")))
        val remote = snapshot(listOf(source("lx_a", hash = "old", updatedAt = "2026-11-17T12:00:00Z")))
        val merged = SyncMerge.merge(null, local, remote)
        assertEquals("new", merged.lxSources.single().scriptHash)
    }

    @Test
    fun enabledToggleSurvives() {
        val local = snapshot(listOf(source("lx_a", enabled = false, updatedAt = "2026-11-18T12:00:00Z")))
        val remote = snapshot(listOf(source("lx_a", enabled = true, updatedAt = "2026-11-17T12:00:00Z")))
        assertFalse(SyncMerge.merge(null, local, remote).lxSources.single().enabled)
    }

    @Test
    fun localOnlySourceIsAddedToRemote() {
        val local = snapshot(listOf(source("lx_a"), source("lx_b", position = 1)))
        val merged = SyncMerge.applyStrategy(local, snapshot(emptyList()), SyncStrategy.Merge)
        assertEquals(listOf("lx_a", "lx_b"), merged.lxSources.map { it.id })
        assertNull(merged.lxSources.first().deletedAt)
    }

    @Test
    fun signatureSeesScriptChange() {
        val before = snapshot(listOf(source("lx_a", hash = "h1")))
        val after = snapshot(listOf(source("lx_a", hash = "h2")))
        assertTrue(snapshotSignature(before) != snapshotSignature(after))
    }
}
