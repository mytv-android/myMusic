package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 平台歌单绑定随歌单同步：一台设备编辑 / 解绑后，其他设备不能还绑着、再用平台内容把修改冲掉。 */
class SyncLinkTest {
    private val kuwo = SyncLink("kuwo", "3567349593", "https://img/cover.jpg")

    private fun track(key: String) = SyncTrack(key = key, provider = key.substringBefore(':'), id = key.substringAfter(':'), title = key)

    private fun playlist(
        tracks: List<String> = listOf("kuwo:1", "kuwo:2"),
        link: SyncLink? = kuwo,
        updatedAt: String = "2026-11-09T08:00:00Z",
        linkKnown: Boolean = true,
    ) = SyncPlaylist(
        id = "p1",
        name = "周杰伦",
        createdAt = "2026-11-01T00:00:00Z",
        updatedAt = updatedAt,
        tracks = tracks.map(::track),
        link = link,
        linkKnown = linkKnown,
    )

    private fun snapshot(playlist: SyncPlaylist, version: Int = SCHEMA_VERSION) =
        SyncSnapshot(version = version, deviceId = "d", updatedAt = "2026-11-09T08:00:00Z", playlists = listOf(playlist))

    @Test
    fun `编辑过的那一版没有绑定，另一台设备合并后也解绑`() {
        val base = snapshot(playlist())
        // 本机（还绑着）没动；另一台设备加了一首，编辑时自动解绑
        val local = snapshot(playlist())
        val remote = snapshot(playlist(tracks = listOf("kuwo:1", "kuwo:2", "local:sha256:ab"), link = null, updatedAt = "2026-11-09T09:00:00Z"))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        val merged = result.playlists.single()
        assertNull(merged.link)
        assertEquals(listOf("kuwo:1", "kuwo:2", "local:sha256:ab"), merged.tracks.map { it.key })
    }

    @Test
    fun `只解除绑定不动歌曲也算修改，会推上去`() {
        val base = snapshot(playlist())
        val local = snapshot(playlist(link = null, updatedAt = "2026-11-09T09:00:00Z"))
        val remote = snapshot(playlist())

        assertNotEquals(snapshotSignature(local), snapshotSignature(remote))
        val merged = SyncMerge.merge(base, local, remote).playlists.single()
        assertNull(merged.link)
    }

    @Test
    fun `另一台设备新绑定的歌单，本机也绑上`() {
        val base = snapshot(playlist(link = null))
        val local = snapshot(playlist(link = null))
        val remote = snapshot(playlist(link = kuwo, updatedAt = "2026-11-09T09:00:00Z"))

        assertEquals(kuwo, SyncMerge.merge(base, local, remote).playlists.single().link)
    }

    @Test
    fun `v2 文件不知道绑定，读进来标成未知`() {
        val raw = """{"schema":"mymusic.library","version":2,"deviceId":"old","playlists":[{"id":"p1","name":"周杰伦","tracks":[]}]}"""
        val v2 = SyncJson.decode(raw)
        assertFalse(v2.playlists.single().linkKnown)

        val v3 = SyncJson.decode(SyncJson.encode(snapshot(playlist())))
        assertTrue(v3.playlists.single().linkKnown)
        assertEquals(kuwo, v3.playlists.single().link)
    }

    @Test
    fun `升级后第一次同步：v2 云端没改，本机的绑定保留并推上去`() {
        // 基线和云端都是升级前写的，没有绑定信息；本机歌单绑着
        val base = snapshot(playlist(link = null), version = 2)
        val local = snapshot(playlist())
        val remote = SyncJson.decode(SyncJson.encode(snapshot(playlist(link = null))).replace("\"version\": 3", "\"version\": 2"))
        assertFalse(remote.playlists.single().linkKnown)

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(kuwo, result.playlists.single().link)
    }

    @Test
    fun `选中 v2 那一版时用本机的绑定补上，不会推一个“没绑定”上去`() {
        val local = playlist()
        val remoteV2 = playlist(tracks = listOf("kuwo:9"), link = null, linkKnown = false)

        val chosen = SyncMerge.resolve(local, remoteV2, SyncStrategy.Remote)!!
        assertTrue(chosen.linkKnown)
        assertEquals(kuwo, chosen.link)
        assertEquals(listOf("kuwo:9"), chosen.tracks.map { it.key })

        val content = SyncMerge.applyStrategy(snapshot(local), snapshot(remoteV2, version = 2), SyncStrategy.Remote)
        assertEquals(kuwo, content.playlists.single().link)
    }

    @Test
    fun `建快照时绑定写进歌单，删掉的歌单不带`() {
        val bound = PlaylistEntity(
            id = "p1",
            name = "周杰伦",
            createdAt = 1L,
            updatedAt = 2L,
            linkProvider = "kuwo",
            linkId = "3567349593",
            linkCover = "https://img/cover.jpg",
            linkSyncedAt = 99L,
        )
        val deleted = bound.copy(id = "p2", deletedAt = 3L)
        val plain = bound.copy(id = "p3", linkProvider = "", linkId = "", linkCover = "")

        val built = buildSnapshot(listOf(bound, deleted, plain), emptyList(), deviceId = "d").playlists.associateBy { it.id }

        assertEquals(kuwo, built.getValue("p1").link)
        assertNull(built.getValue("p2").link)
        assertNull(built.getValue("p3").link)
        // 刷新节流时间是每台设备自己的，不进同步文件
        assertFalse(SyncJson.encode(snapshot(built.getValue("p1"))).contains("99"))
    }

    @Test
    fun `没绑定的歌单签名和升级前一样，不会凭空多出修改`() {
        val before = playlistSignature(playlist(link = null))
        assertEquals(before, playlistSignature(playlist(link = null, linkKnown = false)))
        assertNotEquals(before, playlistSignature(playlist(link = kuwo)))
    }
}
