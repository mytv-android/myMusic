package com.mytv.mymusic.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推送后的收敛判断。Gitee 的 revision 只是秒级 `updated_at`，推送后立刻再读经常对得上内容、
 * 对不上时间戳；这种时候必须认“云端没变”，不能当成别的设备插进来再合并再推一遍——
 * 每推一次 revision 又变一次，几轮之后就只剩“云端文件在同步期间持续变化”。
 */
class SyncConvergenceTest {
    private fun playlist(name: String = "通勤路上") = SyncPlaylist(
        id = "p1",
        name = name,
        createdAt = "2026-11-01T00:00:00Z",
        updatedAt = "2026-11-08T12:00:00Z",
        tracks = emptyList(),
    )

    private fun snapshot(vararg playlists: SyncPlaylist) =
        SyncSnapshot(
            deviceId = "d",
            updatedAt = "2026-11-08T12:00:00Z",
            playlists = playlists.toList(),
        )

    private fun file(content: String, revision: String) =
        RemoteFile(id = "g1", content = content, revision = revision, updatedAt = 1L, htmlUrl = "")

    @Test
    fun sameContentWithMovedTimestampCountsAsUnchanged() {
        val content = SyncJson.encode(snapshot(playlist()))
        // 同一次推送：PATCH 回的时间戳和紧接着 GET 到的不一样（Gitee 是秒级的）
        assertTrue(sameRemoteState(file(content, "10:00:00+08:00"), file(content, "10:00:01+08:00")))
    }

    @Test
    fun realChangeIsStillDetected() {
        val a = SyncJson.encode(snapshot(playlist(name = "通勤路上")))
        val b = SyncJson.encode(snapshot(playlist(name = "深夜公路")))
        assertFalse(sameRemoteState(file(a, "r1"), file(b, "r2")))
    }

    @Test
    fun revisionStillWinsWhenItMatches() {
        assertTrue(sameRemoteState(file("a", "r1"), file("b", "r1")))
    }

    @Test
    fun unreadableContentNeverCountsAsUnchanged() {
        val good = SyncJson.encode(snapshot(playlist()))
        assertFalse(sameRemoteState(file("{ 坏", "r2"), file(good, "r1")))
        assertFalse(sameRemoteState(file(good, "r1"), file("{ 坏", "r2")))
    }

    @Test
    fun cloudHoldingWhatWePushedMeansConverged() {
        val pushed = snapshot(playlist())
        // revision 又跳了一秒，内容正是刚写出去的那一版
        assertTrue(holdsSnapshot(file(SyncJson.encode(pushed), "10:00:01+08:00"), pushed))
    }

    @Test
    fun cloudHoldingAnotherDevicesWriteIsNotConverged() {
        val pushed = snapshot(playlist(name = "通勤路上"))
        val other = snapshot(playlist(name = "深夜公路"))
        assertFalse(holdsSnapshot(file(SyncJson.encode(other), "10:00:01+08:00"), pushed))
    }

    @Test
    fun unreadableCloudFileIsNotConverged() {
        assertFalse(holdsSnapshot(file("{ 坏", "r1"), snapshot(playlist())))
    }

    @Test
    fun giteeRevisionSeparatesWritesInsideTheSameSecond() {
        val at = "2026-11-21T10:00:00+08:00"
        assertNotEquals(giteeRevision(at, "{}"), giteeRevision(at, """{"a":1}"""))
        assertEquals(giteeRevision(at, "{}"), giteeRevision(at, "{}"))
        assertTrue(giteeRevision(at, "{}").startsWith(at))
        // 没有时间戳时退回空串，让 remoteMatches 走时间戳 + 内容那条路
        assertEquals("", giteeRevision("", "{}"))
    }
}
