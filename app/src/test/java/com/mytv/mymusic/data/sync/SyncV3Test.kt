package com.mytv.mymusic.data.sync

import com.mytv.mymusic.data.PlaylistEntity
import com.mytv.mymusic.data.PlaylistEntryEntity
import com.mytv.mymusic.data.SavedSong
import com.mytv.mymusic.data.Song
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 同步 v3：本地歌的三组线索（local / remote / match）。
 * 读得了 v2、v3 往返不丢、线索按规则取并集、哪里都放不了也不删条目、同步文件里没有路径地址密码、超过 1 MB 只提示。
 */
class SyncV3Test {
    private val id = "sha256:" + "9f".repeat(32)
    private val key = "local:$id"

    private fun local(
        local: SyncLocal? = null,
        remote: SyncRemote? = null,
        match: SyncMatch? = null,
        title: String = "雨季备忘",
    ) = SyncTrack(
        key = key,
        provider = "local",
        id = id,
        title = title,
        artist = "示例歌手",
        album = "示例专辑",
        durationSec = 252,
        local = local,
        remote = remote,
        match = match,
    )

    private fun platform(key: String = "netease:1") = SyncTrack(
        key = key,
        provider = key.substringBefore(':'),
        id = key.substringAfter(':'),
        title = "晴天",
        artist = "周杰伦",
    )

    private fun playlist(
        tracks: List<SyncTrack>,
        updatedAt: String = "2026-11-08T12:00:00Z",
        name: String = "通勤路上",
        id: String = "p1",
    ) = SyncPlaylist(
        id = id,
        name = name,
        createdAt = "2026-11-01T00:00:00Z",
        updatedAt = updatedAt,
        tracks = tracks,
    )

    private fun snapshot(vararg playlists: SyncPlaylist, favorites: List<SyncFavorite> = emptyList()) =
        SyncSnapshot(deviceId = "d", updatedAt = "2026-11-08T12:00:00Z", playlists = playlists.toList(), favorites = favorites)

    private val pixel = SyncLocal(hash = id, fileName = "雨季备忘.flac", fileSize = 31_400_000, device = "Pixel 8")
    private val netease = SyncRemote(provider = "netease_cloud", id = "c1", status = "ready", uploadedAt = "2026-11-08T12:00:00Z")
    private val tagMatch = SyncMatch(provider = "netease", id = "186016", confidence = 0.94f, method = "tag", matchedAt = "2026-11-08T11:00:00Z")

    // ---------------------------------------------------------------- 版本

    @Test
    fun v2FileStillDecodes() {
        val raw = """
            {"schema":"mymusic.library","version":2,"deviceId":"old","updatedAt":"2026-10-01T00:00:00Z",
             "playlists":[{"id":"p1","name":"通勤路上","createdAt":"2026-09-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z",
               "tracks":[{"key":"$key","provider":"local","id":"$id","title":"雨季备忘","artist":"示例歌手","album":"","durationSec":252,"cover":""}]}],
             "favorites":[],"cookies":[]}
        """.trimIndent()
        val snapshot = SyncJson.decode(raw)
        assertEquals(2, snapshot.version)
        val track = snapshot.playlists.single().tracks.single()
        assertEquals(key, track.key)
        assertNull(track.local)
        assertNull(track.remote)
        assertNull(track.match)
        assertFalse(track.hasRefs)
    }

    @Test
    fun v3RoundTripsAndOmitsEmptyRefs() {
        val withRefs = local(pixel, netease, tagMatch)
        val original = snapshot(playlist(listOf(withRefs, platform())))
        val raw = SyncJson.encode(original)
        assertTrue(raw.contains("\"version\": 3"))
        val back = SyncJson.decode(raw)
        assertEquals(original.playlists, back.playlists)
        assertEquals(pixel, back.playlists.single().tracks[0].local)
        assertEquals(netease, back.playlists.single().tracks[0].remote)
        assertEquals(tagMatch, back.playlists.single().tracks[0].match)
        // 平台歌不带线索，也不写 "local": null 这类空字段，文件保持小
        val platformJson = Json.encodeToString(platform())
        assertFalse(platformJson.contains("\"local\""))
        assertFalse(platformJson.contains("\"remote\""))
        assertFalse(platformJson.contains("\"match\""))
    }

    @Test
    fun newerVersionAsksToUpdate() {
        val raw = SyncJson.encode(snapshot(playlist(listOf(platform())))).replace("\"version\": 3", "\"version\": 4")
        try {
            SyncJson.decode(raw)
            fail("v4 应该被拒绝")
        } catch (error: SyncException) {
            assertTrue(error.message.orEmpty().contains("请先更新应用"))
        }
    }

    // ---------------------------------------------------------------- 合并规则

    @Test
    fun remoteNewerUploadWinsAndSameUploadDeletedWins() {
        val older = netease
        val newer = SyncRemote("webdav", "myMusic/music/9f/9f.flac", uploadedAt = "2026-11-09T08:00:00Z")
        assertEquals(newer, SyncRefMerge.remote(older, newer))
        assertEquals(newer, SyncRefMerge.remote(newer, older))
        // 同一次上传：删除胜出（不论哪一边）
        val deleted = netease.copy(status = SyncRefMerge.STATUS_DELETED)
        assertEquals(deleted, SyncRefMerge.remote(netease, deleted))
        assertEquals(deleted, SyncRefMerge.remote(deleted, netease))
        // 删除后重新上传：新时间盖过删除
        val reuploaded = netease.copy(uploadedAt = "2026-11-10T00:00:00Z")
        assertEquals(reuploaded, SyncRefMerge.remote(deleted, reuploaded))
    }

    @Test
    fun matchHigherConfidenceWinsThenNewer() {
        val manual = SyncMatch("qq", "003", confidence = 1f, method = "manual", matchedAt = "2026-11-01T00:00:00Z")
        // 手选的记满分，比更晚的自动匹配优先
        assertEquals(manual, SyncRefMerge.match(tagMatch, manual))
        assertEquals(manual, SyncRefMerge.match(manual, tagMatch))
        val sameScoreNewer = tagMatch.copy(id = "999", matchedAt = "2026-11-09T00:00:00Z")
        assertEquals(sameScoreNewer, SyncRefMerge.match(tagMatch, sameScoreNewer))
        assertEquals(sameScoreNewer, SyncRefMerge.match(sameScoreNewer, tagMatch))
    }

    @Test
    fun localNonEmptyFieldsWin() {
        val partial = SyncLocal(hash = id, fileName = "", fileSize = 0, device = "Mi 13")
        val merged = SyncRefMerge.local(pixel.copy(device = ""), partial)
        assertEquals(SyncLocal(id, "雨季备忘.flac", 31_400_000, "Mi 13"), merged)
        assertEquals(pixel, SyncRefMerge.local(null, pixel))
        assertEquals(pixel, SyncRefMerge.local(pixel, SyncLocal()))
    }

    @Test
    fun mergeUnitesRefsOntoChosenPlaylistAndFavorites() {
        val base = snapshot(playlist(listOf(local())))
        // 本机改了歌单名（更新），云端那边给这首记了云端位置和匹配
        val mine = snapshot(
            playlist(listOf(local(local = pixel)), updatedAt = "2026-11-09T09:00:00Z", name = "通勤"),
            favorites = listOf(SyncFavorite(key, addedAt = "2026-11-02T00:00:00Z", track = local(local = pixel))),
        )
        val theirs = snapshot(
            playlist(listOf(local(remote = netease, match = tagMatch))),
            favorites = listOf(SyncFavorite(key, addedAt = "2026-11-02T00:00:00Z", track = local(remote = netease, match = tagMatch))),
        )
        val result = SyncMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        val merged = result.playlists.single()
        assertEquals("通勤", merged.name)
        val track = merged.tracks.single()
        assertEquals(pixel, track.local)
        assertEquals(netease, track.remote)
        assertEquals(tagMatch, track.match)
        val favorite = result.favorites.single().track!!
        assertEquals(pixel, favorite.local)
        assertEquals(netease, favorite.remote)
        assertEquals(tagMatch, favorite.match)
    }

    @Test
    fun refsOnlyChangeIsNotAConflictButChangesSignature() {
        val base = snapshot(playlist(listOf(local(local = pixel))))
        val mine = snapshot(playlist(listOf(local(local = pixel, match = tagMatch))))
        val theirs = snapshot(playlist(listOf(local(local = pixel, remote = netease))))
        val result = SyncMerge.merge(base, mine, theirs)
        assertTrue(result.conflicts.isEmpty())
        val track = result.playlists.single().tracks.single()
        assertEquals(tagMatch, track.match)
        assertEquals(netease, track.remote)
        assertEquals(playlistSignature(base.playlists.single()), playlistSignature(mine.playlists.single()))
        assertNotEquals(snapshotSignature(base), snapshotSignature(mine))
    }

    @Test
    fun strategyChoiceStillUnitesRefs() {
        val mine = playlist(listOf(local(local = pixel)), name = "本机版")
        val theirs = playlist(listOf(local(remote = netease)), name = "云端版")
        val kept = SyncMerge.resolve(mine, theirs, SyncStrategy.Local)!!
        assertEquals("本机版", kept.name)
        assertEquals(netease, kept.tracks.single().remote)
        assertEquals(pixel, kept.tracks.single().local)
    }

    @Test
    fun unplayableEverywhereNeverRemovesEntries() {
        // 哪台设备都放不了：云端副本已删、没有匹配、文件只在一台设备上。条目一首都不能少
        val gone = local(local = pixel, remote = netease.copy(status = SyncRefMerge.STATUS_DELETED))
        val base = snapshot(playlist(listOf(gone, platform())))
        val mine = snapshot(playlist(listOf(gone, platform())))
        val theirs = snapshot(playlist(listOf(local(local = pixel.copy(device = "")), platform())))
        val result = SyncMerge.merge(base, mine, theirs)
        assertEquals(listOf(key, "netease:1"), result.playlists.single().tracks.map { it.key })
        // 线索合并只换线索，不增删歌
        val (playlists, _) = SyncRefMerge.unite(
            listOf(playlist(listOf(gone, platform()))),
            emptyList(),
            mapOf(key to gone),
            mapOf(key to local(remote = netease.copy(status = SyncRefMerge.STATUS_DELETED, uploadedAt = "2026-12-01T00:00:00Z"))),
        )
        assertEquals(2, playlists.single().tracks.size)
    }

    @Test
    fun cloudDeleteMarksRemoteDeleted() {
        val deleted = netease.copy(status = SyncRefMerge.STATUS_DELETED)
        val base = snapshot(playlist(listOf(local(local = pixel, remote = netease))))
        // 一台设备删了网易云盘里的副本：同一次上传标成 deleted
        val mine = snapshot(playlist(listOf(local(local = pixel, remote = deleted))))
        val theirs = snapshot(playlist(listOf(local(local = pixel, remote = netease))))
        val result = SyncMerge.merge(base, mine, theirs)
        val track = result.playlists.single().tracks.single()
        assertEquals(SyncRefMerge.STATUS_DELETED, track.remote?.status)
        assertEquals(key, track.key)
    }

    // ---------------------------------------------------------------- 快照内容

    @Test
    fun snapshotCarriesRefsButNoPathsUrlsOrPasswords() {
        val json = Json { encodeDefaults = true }
        // 旧数据里可能还留着本机地址和封面缓存路径：同步文件里一律不能出现
        val stored = Song(
            id = id,
            name = "雨季备忘",
            artist = "示例歌手",
            source = "local",
            duration = 252,
            url = "https://m801.music.126.net/20261109/cloud-signed.flac?token=abc",
            cover = "file:///data/user/0/com.mytv.mymusic/files/local_covers/9f.jpg",
            extra = mapOf("local_file" to "雨季备忘.flac"),
        )
        val contentSong = stored.copy(id = "sha256:" + "aa".repeat(32), url = "content://media/external/audio/media/42")
        val playlists = listOf(PlaylistEntity(id = "p1", name = "通勤路上", createdAt = 1, updatedAt = 2))
        val entries = listOf(
            PlaylistEntryEntity("p1", 0, stored.key, json.encodeToString(stored)),
            PlaylistEntryEntity("p1", 1, contentSong.key, json.encodeToString(contentSong)),
        )
        val refs = mapOf(
            stored.key to SyncTrackRefs(
                local = pixel,
                remote = SyncRemote("webdav", "myMusic/music/9f/9f.flac", uploadedAt = "2026-11-08T12:00:00Z"),
                match = tagMatch,
                cover = "https://p1.music.126.net/cover.jpg",
            ),
        )
        val snapshot = buildSnapshot(
            playlists = playlists,
            entries = entries,
            deviceId = "d",
            favorites = listOf(SavedSong(stored.key, "favorite", json.encodeToString(stored), 3)),
            localRefs = refs,
        )
        val raw = SyncJson.encode(snapshot)
        val password = "hunter2-webdav-secret"
        listOf("content://", "file://", "/storage/", "local_covers", "cloud-signed", "token=abc", password, "password").forEach {
            assertFalse("同步文件里不该有 $it", raw.contains(it))
        }
        val track = snapshot.playlists.single().tracks.first()
        assertEquals(pixel, track.local)
        assertEquals("webdav", track.remote?.provider)
        assertEquals(tagMatch, track.match)
        // 本地歌的封面用匹配到的平台封面网址
        assertEquals("https://p1.music.126.net/cover.jpg", track.cover)
        assertEquals("", snapshot.playlists.single().tracks[1].cover)
        assertEquals(pixel, snapshot.favorites.single().track?.local)
    }

    // ---------------------------------------------------------------- 体积

    @Test
    fun sizeWarningOnlyAboveOneMegabyte() {
        assertEquals("", SyncSizeBudget.warning(SyncSizeBudget.WARN_BYTES))
        val notice = SyncSizeBudget.warning(SyncSizeBudget.WARN_BYTES + 1)
        assertTrue(notice.contains("1.0 MB"))
        assertTrue(notice.contains("同步照常进行"))
        assertTrue(SyncSizeBudget.warning(3 * 1024 * 1024).contains("3.0 MB"))
        // Gist / Gitee 读不全太大的文件，仍在 900 KB 停；WebDAV 放到 4 MB
        assertEquals(900 * 1024, SyncSizeBudget.hardLimitFor("gist"))
        assertEquals(4 * 1024 * 1024, SyncSizeBudget.hardLimitFor(WebDavSyncProvider.ID))
    }
}
