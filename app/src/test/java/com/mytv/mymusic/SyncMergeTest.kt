package com.mytv.mymusic

import com.mytv.mymusic.data.sync.SyncCookie
import com.mytv.mymusic.data.sync.SyncFavorite
import com.mytv.mymusic.data.sync.SyncJson
import com.mytv.mymusic.data.sync.SyncMerge
import com.mytv.mymusic.data.sync.SyncPlaylist
import com.mytv.mymusic.data.sync.SyncSnapshot
import com.mytv.mymusic.data.sync.SyncSodaIdentity
import com.mytv.mymusic.data.sync.SyncStrategy
import com.mytv.mymusic.data.sync.SyncTrack
import com.mytv.mymusic.data.sync.snapshotSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun track(key: String) = SyncTrack(
        key = key,
        provider = key.substringBefore(':'),
        id = key.substringAfter(':'),
        title = "Song $key",
        artist = "Artist",
        album = "Album",
        durationSec = 200,
    )

    private fun playlist(
        id: String = "p1",
        name: String = "夜间驾驶",
        tracks: List<String> = listOf("netease:1"),
        deletedAt: String? = null,
        updatedAt: String = "2026-10-20T22:40:10Z",
    ) = SyncPlaylist(
        id = id,
        name = name,
        createdAt = "2026-09-03T12:10:00Z",
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        tracks = tracks.map(::track),
    )

    private fun snapshot(vararg playlists: SyncPlaylist) = SyncSnapshot(
        deviceId = "device-a",
        updatedAt = "2026-10-21T02:19:41Z",
        playlists = playlists.toList(),
    )

    private fun favorite(
        key: String,
        addedAt: String = "2026-10-20T10:00:00Z",
        deletedAt: String? = null,
    ) = SyncFavorite(
        key = key,
        addedAt = addedAt,
        deletedAt = deletedAt,
        track = if (deletedAt == null) track(key) else null,
    )

    private fun cookie(
        provider: String,
        value: String = "session=$provider",
        updatedAt: String = "2026-10-20T10:00:00Z",
        deletedAt: String? = null,
    ) = SyncCookie(provider = provider, value = value, updatedAt = updatedAt, deletedAt = deletedAt)

    @Test
    fun takesLocalChangesWhenRemoteIsUnchanged() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))
        val remote = snapshot(playlist(tracks = listOf("netease:1")))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("netease:1", "netease:2"), result.playlists.single().tracks.map { it.key })
    }

    @Test
    fun takesRemoteChangesWhenLocalIsUnchanged() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1")))
        val remote = snapshot(playlist(name = "新的名字", tracks = listOf("netease:1", "qq:9")))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals("新的名字", result.playlists.single().name)
        assertEquals(2, result.playlists.single().tracks.size)
    }

    @Test
    fun reportsConflictWhenBothSidesChangeDifferently() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))
        val remote = snapshot(playlist(tracks = listOf("netease:1", "qq:9")))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals(1, result.conflicts.size)
        assertTrue(result.playlists.isEmpty())
        assertEquals("p1", result.conflicts.single().playlistId)
    }

    @Test
    fun doesNotConflictWhenBothSidesMadeTheSameChange() {
        val base = snapshot(playlist(tracks = listOf("netease:1")))
        val local = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))
        val remote = snapshot(playlist(tracks = listOf("netease:1", "netease:2")))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.playlists.single().tracks.size)
    }

    @Test
    fun localDeletionPropagatesWhenRemoteIsUnchanged() {
        val base = snapshot(playlist())
        val local = snapshot(playlist(deletedAt = "2026-10-21T01:00:00Z"))
        val remote = snapshot(playlist())

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals("2026-10-21T01:00:00Z", result.playlists.single().deletedAt)
        assertTrue(result.playlists.single().tracks.isEmpty())
    }

    @Test
    fun deletionVersusRemoteEditIsConflict() {
        val base = snapshot(playlist())
        val local = snapshot(playlist(deletedAt = "2026-10-21T01:00:00Z"))
        val remote = snapshot(playlist(tracks = listOf("netease:1", "qq:9")))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals(1, result.conflicts.size)
    }

    @Test
    fun mergeResolutionKeepsLocalOrderAndAppendsRemoteOnlyTracks() {
        val local = playlist(tracks = listOf("netease:1", "netease:2"))
        val remote = playlist(tracks = listOf("netease:2", "netease:3", "qq:9"))

        val merged = SyncMerge.resolve(local, remote, SyncStrategy.Merge)!!

        assertEquals(listOf("netease:1", "netease:2", "netease:3", "qq:9"), merged.tracks.map { it.key })
        assertEquals("夜间驾驶", merged.name)
    }

    @Test
    fun mergeResolutionDoesNotRestoreDeletedSide() {
        val local = playlist(deletedAt = "2026-10-21T01:00:00Z")
        val remote = playlist(tracks = listOf("netease:1", "netease:2"))

        val merged = SyncMerge.resolve(local, remote, SyncStrategy.Merge)!!

        assertNull(merged.deletedAt)
        assertEquals(2, merged.tracks.size)
    }

    @Test
    fun initialStrategyMergeUnionsBothLibraries() {
        val local = snapshot(playlist(id = "p1", tracks = listOf("netease:1")))
        val remote = snapshot(
            playlist(id = "p1", tracks = listOf("netease:1", "qq:9")),
            playlist(id = "p2", name = "云端歌单", tracks = listOf("kugou:3")),
        )

        val content = SyncMerge.applyStrategy(local, remote, SyncStrategy.Merge)

        assertEquals(2, content.playlists.size)
        assertEquals(listOf("netease:1", "qq:9"), content.playlists.first { it.id == "p1" }.tracks.map { it.key })
        assertEquals("云端歌单", content.playlists.first { it.id == "p2" }.name)
    }

    @Test
    fun localFavoriteAdditionPropagates() {
        val base = snapshot()
        val local = snapshot().copy(favorites = listOf(favorite("netease:1")))
        val remote = snapshot()

        val result = SyncMerge.merge(base, local, remote)

        assertEquals(listOf("netease:1"), result.favorites.map { it.key })
        assertNull(result.favorites.single().deletedAt)
    }

    @Test
    fun favoriteDeletionTombstonePropagates() {
        val base = snapshot().copy(favorites = listOf(favorite("netease:1")))
        val local = snapshot().copy(
            favorites = listOf(favorite("netease:1", deletedAt = "2026-10-21T01:00:00Z")),
        )
        val remote = snapshot().copy(favorites = listOf(favorite("netease:1")))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("2026-10-21T01:00:00Z", result.favorites.single().deletedAt)
        assertNull(result.favorites.single().track)
    }

    @Test
    fun favoriteLastWriteWinsWhenBothSidesChange() {
        val base = snapshot()
        val local = snapshot().copy(favorites = listOf(favorite("netease:1", addedAt = "2026-10-21T02:00:00Z")))
        val remote = snapshot().copy(
            favorites = listOf(favorite("netease:1", addedAt = "2026-10-20T10:00:00Z", deletedAt = "2026-10-21T03:00:00Z")),
        )

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("2026-10-21T03:00:00Z", result.favorites.single().deletedAt)
    }

    @Test
    fun cookieLoginFromRemoteAppliesWhenLocalUnchanged() {
        val base = snapshot()
        val local = snapshot()
        val remote = snapshot().copy(cookies = listOf(cookie("netease")))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("netease", result.cookies.single().provider)
        assertEquals("session=netease", result.cookies.single().value)
    }

    @Test
    fun cookieLogoutTombstoneBeatsOlderRemoteCookie() {
        val base = snapshot().copy(cookies = listOf(cookie("netease")))
        val local = snapshot().copy(
            cookies = listOf(
                cookie("netease", updatedAt = "2026-10-21T03:00:00Z", deletedAt = "2026-10-21T03:00:00Z", value = ""),
            ),
        )
        val remote = snapshot().copy(cookies = listOf(cookie("netease")))

        val result = SyncMerge.merge(base, local, remote)

        assertTrue(result.cookies.single().value.isBlank())
        assertEquals("2026-10-21T03:00:00Z", result.cookies.single().deletedAt)
    }

    @Test
    fun initialMergeAddsRemoteFavoritesAndKeepsLocalLogout() {
        val local = snapshot().copy(
            favorites = listOf(favorite("netease:1")),
            cookies = listOf(cookie("kugou", updatedAt = "2026-10-21T02:00:00Z", deletedAt = "2026-10-21T02:00:00Z", value = "")),
        )
        val remote = snapshot().copy(
            favorites = listOf(favorite("qq:9")),
            cookies = listOf(cookie("kugou"), cookie("netease")),
        )

        val content = SyncMerge.applyStrategy(local, remote, SyncStrategy.Merge)

        assertEquals(setOf("netease:1", "qq:9"), content.favorites.map { it.key }.toSet())
        assertEquals(setOf("kugou", "netease"), content.cookies.map { it.provider }.toSet())
        assertTrue(content.cookies.first { it.provider == "kugou" }.value.isBlank())
        assertEquals("session=netease", content.cookies.first { it.provider == "netease" }.value)
    }

    @Test
    fun snapshotJsonRoundTripsFavoritesAndCookies() {
        val snapshot = snapshot().copy(
            favorites = listOf(
                favorite("netease:1"),
                favorite("qq:9", addedAt = "2026-10-21T01:00:00Z", deletedAt = "2026-10-21T01:00:00Z"),
            ),
            cookies = listOf(
                cookie("netease"),
                cookie("kugou", updatedAt = "2026-10-21T01:00:00Z", deletedAt = "2026-10-21T01:00:00Z", value = ""),
            ),
        )

        val decoded = SyncJson.decode(SyncJson.encode(snapshot))

        assertEquals(2, decoded.favorites.size)
        assertEquals("netease:1", decoded.favorites.first { it.deletedAt == null }.key)
        assertEquals("2026-10-21T01:00:00Z", decoded.favorites.first { it.key == "qq:9" }.deletedAt)
        assertEquals("session=netease", decoded.cookies.first { it.provider == "netease" }.value)
        assertEquals("2026-10-21T01:00:00Z", decoded.cookies.first { it.provider == "kugou" }.deletedAt)
    }

    @Test
    fun versionOneFileStillDecodes() {
        val raw = """
            {"schema":"mymusic.library","version":1,"deviceId":"device-a",
            "updatedAt":"2026-10-21T02:19:41Z","playlists":[]}
        """.trimIndent()

        val decoded = SyncJson.decode(raw)

        assertTrue(decoded.favorites.isEmpty())
        assertTrue(decoded.cookies.isEmpty())
    }

    @Test
    fun snapshotSignatureDetectsFavoriteAndCookieChanges() {
        val base = snapshot()
        assertNotEquals(snapshotSignature(base), snapshotSignature(base.copy(favorites = listOf(favorite("netease:1")))))
        assertNotEquals(snapshotSignature(base), snapshotSignature(base.copy(cookies = listOf(cookie("netease")))))
        assertEquals(
            snapshotSignature(base.copy(cookies = listOf(cookie("netease")))),
            snapshotSignature(base.copy(cookies = listOf(cookie("netease", updatedAt = "2026-10-22T00:00:00Z")))),
        )
    }

    @Test
    fun snapshotJsonRoundTripsTombstones() {
        val snapshot = snapshot(
            playlist(id = "p1", deletedAt = "2026-10-21T01:00:00Z"),
            playlist(id = "p2", tracks = listOf("netease:2", "qq:9")),
        )
        val raw = SyncJson.encode(snapshot)
        val decoded = SyncJson.decode(raw)

        assertEquals(snapshot.schema, decoded.schema)
        assertEquals(2, decoded.playlists.size)
        assertEquals("2026-10-21T01:00:00Z", decoded.playlists.first { it.id == "p1" }.deletedAt)
        assertEquals(listOf("netease:2", "qq:9"), decoded.playlists.first { it.id == "p2" }.tracks.map { it.key })
    }

    // ---- 汽水登录设备身份：和登录 Cookie 是同一套凭据，必须一起同步 ----

    private fun sodaIdentity(
        deviceId: String = "9182736455463728",
        installId: String = "123456789012345",
        reportDeviceId: String = "a1b2c3d4e5f6",
        updatedAt: String = "2026-10-20T10:00:00Z",
    ) = SyncSodaIdentity(deviceId = deviceId, installId = installId, reportDeviceId = reportDeviceId, updatedAt = updatedAt)

    @Test
    fun remoteLoginDeviceAppliesWhenLocalHasNone() {
        val base = snapshot()
        val local = snapshot()
        val remote = snapshot().copy(sodaIdentity = sodaIdentity())

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("9182736455463728", result.sodaIdentity?.deviceId)
        assertEquals("123456789012345", result.sodaIdentity?.installId)
    }

    @Test
    fun localLoginDeviceSurvivesRemoteWithoutOne() {
        val base = snapshot()
        val local = snapshot().copy(sodaIdentity = sodaIdentity())
        val remote = snapshot()

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("9182736455463728", result.sodaIdentity?.deviceId)
    }

    @Test
    fun newerRotatedLoginDeviceWins() {
        val base = snapshot().copy(sodaIdentity = sodaIdentity())
        // 本机轮换过（更新），另一台还是旧身份：取新的
        val local = snapshot().copy(
            sodaIdentity = sodaIdentity(deviceId = "5555555555555555", updatedAt = "2026-10-22T09:00:00Z"),
        )
        val remote = snapshot().copy(sodaIdentity = sodaIdentity())

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("5555555555555555", result.sodaIdentity?.deviceId)
    }

    @Test
    fun olderLoginDeviceDoesNotOverwriteNewerLocal() {
        val base = snapshot().copy(sodaIdentity = sodaIdentity())
        val local = snapshot().copy(
            sodaIdentity = sodaIdentity(deviceId = "5555555555555555", updatedAt = "2026-10-22T09:00:00Z"),
        )
        // 云端是轮换前那一版：旧快照盖不掉本机的新身份
        val remote = snapshot().copy(sodaIdentity = sodaIdentity())

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("5555555555555555", result.sodaIdentity?.deviceId)
    }

    @Test
    fun sameLoginDeviceOnBothSidesIsNotAConflict() {
        val base = snapshot()
        val local = snapshot().copy(sodaIdentity = sodaIdentity())
        val remote = snapshot().copy(sodaIdentity = sodaIdentity())

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("9182736455463728", result.sodaIdentity?.deviceId)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun oldSyncFileWithoutLoginDeviceLeavesLocalAlone() {
        val base = snapshot()
        val local = snapshot().copy(sodaIdentity = sodaIdentity())
        // 老同步文件没有这一项（null）：本机保持自己那份
        val remote = snapshot().copy(sodaIdentity = null)

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("9182736455463728", result.sodaIdentity?.deviceId)
    }

    @Test
    fun initialMergeStrategiesPickTheRightLoginDevice() {
        val local = snapshot().copy(sodaIdentity = sodaIdentity(deviceId = "1111111111111111"))
        val remote = snapshot().copy(
            sodaIdentity = sodaIdentity(deviceId = "2222222222222222", updatedAt = "2026-10-23T10:00:00Z"),
        )
        assertEquals(
            "1111111111111111",
            SyncMerge.applyStrategy(local, remote, SyncStrategy.Local).sodaIdentity?.deviceId,
        )
        assertEquals(
            "2222222222222222",
            SyncMerge.applyStrategy(local, remote, SyncStrategy.Remote).sodaIdentity?.deviceId,
        )
        assertEquals(
            "2222222222222222",
            SyncMerge.applyStrategy(local, remote, SyncStrategy.Merge).sodaIdentity?.deviceId,
        )
    }

    @Test
    fun halfFilledLoginDeviceIsIgnored() {
        // 只有一个字段（坏数据 / 老格式）不当可用：不能顶掉另一边完整的那份
        val half = SyncSodaIdentity(deviceId = "9182736455463728")
        assertEquals(
            "9182736455463728",
            SyncMerge.mergeSodaIdentity(sodaIdentity(), half)?.deviceId,
        )
        assertEquals(
            "123456789012345",
            SyncMerge.mergeSodaIdentity(SyncSodaIdentity(installId = "123456789012345"), sodaIdentity())?.installId,
        )
        assertNull(SyncMerge.mergeSodaIdentity(half, SyncSodaIdentity(installId = "123456789012345")))
        assertNull(SyncMerge.mergeSodaIdentity(null, SyncSodaIdentity()))
    }

    @Test
    fun remoteReportFingerprintAppliesWithLoginIdentity() {
        val base = snapshot()
        val local = snapshot()
        val remote = snapshot().copy(sodaIdentity = sodaIdentity(reportDeviceId = "ffffffffffff"))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("9182736455463728", result.sodaIdentity?.deviceId)
        assertEquals("ffffffffffff", result.sodaIdentity?.reportDeviceId)
    }

    @Test
    fun blankReportFingerprintDoesNotEraseLocalOne() {
        // 另一台没开播放上报：它那份身份赢了，但不能把本机的指纹抹掉——
        // 抹了业务请求又会退化成「每次都是一个新设备号」
        val base = snapshot()
        val local = snapshot().copy(sodaIdentity = sodaIdentity(reportDeviceId = "localreport01"))
        val remote = snapshot().copy(
            sodaIdentity = sodaIdentity(
                deviceId = "5555555555555555",
                reportDeviceId = "",
                updatedAt = "2026-10-22T09:00:00Z",
            ),
        )

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("5555555555555555", result.sodaIdentity?.deviceId)
        assertEquals("localreport01", result.sodaIdentity?.reportDeviceId)
    }

    @Test
    fun newerReportFingerprintWins() {
        val base = snapshot().copy(sodaIdentity = sodaIdentity(reportDeviceId = "oldreport001"))
        val local = snapshot().copy(
            sodaIdentity = sodaIdentity(reportDeviceId = "newreport001", updatedAt = "2026-10-22T09:00:00Z"),
        )
        val remote = snapshot().copy(sodaIdentity = sodaIdentity(reportDeviceId = "oldreport001"))

        val result = SyncMerge.merge(base, local, remote)

        assertEquals("newreport001", result.sodaIdentity?.reportDeviceId)
    }
}
