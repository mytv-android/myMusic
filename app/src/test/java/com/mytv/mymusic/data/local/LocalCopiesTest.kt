package com.mytv.mymusic.data.local

import com.mytv.mymusic.aggregate.Availability
import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudPage
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.CloudRef
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.cloud.CloudStorage
import com.mytv.mymusic.data.cloud.CloudStream
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析顺序：本机文件 → 云端副本（网易云盘 → WebDAV → QQ 云盘）→ 平台版本（可关）→ 都没有。
 * 云端失败只记在本机（404 / 要登录 / 连不上），不删条目；徽标和解析用同一套顺序。
 */
class LocalCopiesTest {
    private val id = "sha256:" + "9f".repeat(32)
    private val song = Song(id = id, name = "雨季备忘", artist = "示例歌手", source = LOCAL_SOURCE)
    private val match = Song(id = "186016", name = "雨季备忘", artist = "示例歌手", source = "netease")
    private val now = 1_000_000_000L

    private class FakeStorage(
        override val provider: CloudProvider,
        var outcome: (CloudRef) -> CloudStream,
    ) : CloudStorage {
        val calls = mutableListOf<CloudRef>()
        override suspend fun resolvePlayback(ref: CloudRef): CloudStream {
            calls += ref
            return outcome(ref)
        }
        override suspend fun list(cursor: String?): CloudPage = CloudPage(emptyList())
        override suspend fun quota(): CloudQuota? = null
        override suspend fun delete(ref: CloudRef) = Unit
        override suspend fun upload(source: CloudUploadSource, meta: CloudUploadMeta, progress: (CloudUploadProgress) -> Unit): CloudRef =
            throw UnsupportedOperationException()
    }

    private fun ok(provider: CloudProvider) = FakeStorage(provider) { CloudStream(url = "https://${provider.id}.example/${it.id}") }
    private fun failing(provider: CloudProvider, kind: CloudErrorKind, message: String = "失败") =
        FakeStorage(provider) { throw CloudException(kind, message, provider) }

    private fun remote(provider: CloudProvider, playableId: String = "", status: String = CloudRefStatus.READY, check: String = "", checkedAt: Long = 0) =
        LocalRemoteEntity(
            localId = id,
            provider = provider.id,
            remoteId = "${provider.id}-file",
            playableId = playableId,
            status = status,
            uploadedAt = "2026-11-08T12:00:00Z",
            checkState = check,
            checkedAt = checkedAt,
        )

    private class Recorder {
        val checks = mutableListOf<Triple<String, String, String>>()
    }

    private fun chain(
        hasFile: Boolean,
        storages: Map<CloudProvider, CloudStorage>,
        recorder: Recorder = Recorder(),
    ): List<LocalResolveStep> = listOf(
        // 本机文件：真实实现要读文件，这里只看“这台设备有没有”
        LocalResolveStep { request -> if (hasFile && !request.skipFile) LocalPlayable.File("content://media/1", request.song) else null },
        LocalCloudStep(
            storage = { storages[it] },
            record = { localId, provider, state -> recorder.checks += Triple(localId, provider, state) },
            now = { now },
        ),
        LocalPlatformStep,
    )

    private fun request(
        remotes: List<LocalRemoteEntity> = emptyList(),
        refMatch: Song? = null,
        platformFallback: Boolean = true,
        skipFile: Boolean = false,
        skipCloud: Boolean = false,
    ) = LocalResolveRequest(
        song = song,
        row = null,
        item = null,
        prefs = LocalMusicSettings(platformFallback = platformFallback),
        skipFile = skipFile,
        ref = refMatch?.let { LocalRefEntity(localId = id, matchProvider = it.source, matchId = it.id, matchConfidence = 0.9f) },
        remotes = remotes,
        refMatch = refMatch,
        skipCloud = skipCloud,
    )

    private fun resolve(steps: List<LocalResolveStep>, request: LocalResolveRequest) = runBlocking { runResolveChain(steps, request) }

    @Test
    fun deviceFileComesFirst() {
        val netease = ok(CloudProvider.NeteaseCloud)
        val outcome = resolve(
            chain(hasFile = true, storages = mapOf(CloudProvider.NeteaseCloud to netease)),
            request(remotes = listOf(remote(CloudProvider.NeteaseCloud)), refMatch = match),
        )
        assertTrue(outcome is LocalPlayable.File)
        assertTrue("有文件就不去碰云端", netease.calls.isEmpty())
    }

    @Test
    fun cloudBeforePlatformInProviderOrder() {
        val netease = ok(CloudProvider.NeteaseCloud)
        val webdav = ok(CloudProvider.WebDav)
        val outcome = resolve(
            chain(hasFile = false, storages = mapOf(CloudProvider.NeteaseCloud to netease, CloudProvider.WebDav to webdav)),
            // 列表顺序故意反过来：顺序由规则决定，不由数据库行序决定
            request(remotes = listOf(remote(CloudProvider.WebDav), remote(CloudProvider.NeteaseCloud)), refMatch = match),
        )
        assertTrue(outcome is LocalPlayable.Cloud)
        assertEquals(CloudProvider.NeteaseCloud.id, (outcome as LocalPlayable.Cloud).ref.provider)
        assertEquals(id, outcome.ref.localId)
        assertTrue(webdav.calls.isEmpty())
    }

    @Test
    fun networkFailureFallsThroughToNextCloudAndRecordsBackoff() {
        val recorder = Recorder()
        val netease = failing(CloudProvider.NeteaseCloud, CloudErrorKind.Network)
        val webdav = ok(CloudProvider.WebDav)
        val outcome = resolve(
            chain(false, mapOf(CloudProvider.NeteaseCloud to netease, CloudProvider.WebDav to webdav), recorder),
            request(remotes = listOf(remote(CloudProvider.NeteaseCloud), remote(CloudProvider.WebDav))),
        )
        assertEquals(CloudProvider.WebDav.id, (outcome as LocalPlayable.Cloud).ref.provider)
        assertEquals(listOf(Triple(id, "netease_cloud", LocalCloudCheck.UNREACHABLE)), recorder.checks)
    }

    @Test
    fun platformVersionWhenNoCloudCopy() {
        val outcome = resolve(chain(false, emptyMap()), request(refMatch = match))
        assertEquals(match, (outcome as LocalPlayable.Platform).song)
    }

    @Test
    fun settingOffSkipsPlatform() {
        val req = request(refMatch = match, platformFallback = false)
        assertNull(resolve(chain(false, emptyMap()), req))
    }

    @Test
    fun nothingAnywhereIsNone() {
        val req = request()
        assertNull(resolve(chain(false, emptyMap()), req))
        assertTrue(req.notes.isEmpty())
    }

    @Test
    fun qqUnmatchedIsUnavailableWithoutRequest() {
        val qq = ok(CloudProvider.QqCloud)
        val req = request(remotes = listOf(remote(CloudProvider.QqCloud, playableId = "")))
        assertNull(resolve(chain(false, mapOf(CloudProvider.QqCloud to qq)), req))
        assertEquals(listOf(CloudMessages.QQ_ORIGINAL_UNPLAYABLE), req.notes)
        assertTrue(qq.calls.isEmpty())
        // 匹配上曲库的能放
        val matched = request(remotes = listOf(remote(CloudProvider.QqCloud, playableId = "003OUlho2HcRHC")))
        assertTrue(resolve(chain(false, mapOf(CloudProvider.QqCloud to qq)), matched) is LocalPlayable.Cloud)
    }

    @Test
    fun notFoundMarksMissingKeepsTrackAndFallsBackToPlatform() {
        val recorder = Recorder()
        val netease = failing(CloudProvider.NeteaseCloud, CloudErrorKind.NotFound, CloudMessages.CLOUD_DELETED)
        val req = request(remotes = listOf(remote(CloudProvider.NeteaseCloud)), refMatch = match)
        val outcome = resolve(chain(false, mapOf(CloudProvider.NeteaseCloud to netease), recorder), req)
        assertEquals(listOf(Triple(id, "netease_cloud", LocalCloudCheck.MISSING)), recorder.checks)
        assertTrue(outcome is LocalPlayable.Platform)
        assertEquals(CloudMessages.CLOUD_DELETED, req.notes.first())
        // 记下 404 后一小时内不再撞
        val blocked = request(remotes = listOf(remote(CloudProvider.NeteaseCloud, check = LocalCloudCheck.MISSING, checkedAt = now - 60_000)))
        assertNull(resolve(chain(false, mapOf(CloudProvider.NeteaseCloud to netease)), blocked))
        assertEquals(1, netease.calls.size)
        assertEquals(listOf(CloudMessages.CLOUD_DELETED), blocked.notes)
    }

    @Test
    fun authFailureKeepsRefAndAsksToLogin() {
        val recorder = Recorder()
        val webdav = failing(CloudProvider.WebDav, CloudErrorKind.Unauthorized, CloudMessages.WEBDAV_UNAUTHORIZED)
        val req = request(remotes = listOf(remote(CloudProvider.WebDav)))
        assertNull(resolve(chain(false, mapOf(CloudProvider.WebDav to webdav), recorder), req))
        assertEquals(LocalCloudCheck.AUTH, recorder.checks.single().third)
        assertEquals(CloudMessages.WEBDAV_UNAUTHORIZED, req.notes.single())
    }

    @Test
    fun deletedCopyAndSkipCloudAreSkipped() {
        val netease = ok(CloudProvider.NeteaseCloud)
        val deleted = request(remotes = listOf(remote(CloudProvider.NeteaseCloud, status = CloudRefStatus.DELETED)))
        assertNull(resolve(chain(false, mapOf(CloudProvider.NeteaseCloud to netease)), deleted))
        assertEquals(listOf(CloudMessages.CLOUD_DELETED), deleted.notes)
        // 刚放失败的是同一份内容：文件和云端都跳过，直接平台版本
        val skipped = request(remotes = listOf(remote(CloudProvider.NeteaseCloud)), refMatch = match, skipFile = true, skipCloud = true)
        assertTrue(resolve(chain(true, mapOf(CloudProvider.NeteaseCloud to netease)), skipped) is LocalPlayable.Platform)
        assertTrue(netease.calls.isEmpty())
    }

    @Test
    fun cloudNotConfiguredOnThisDevice() {
        val req = request(remotes = listOf(remote(CloudProvider.NeteaseCloud)))
        assertNull(resolve(chain(false, emptyMap()), req))
        assertEquals(CloudMessages.notLoggedIn(CloudProvider.NeteaseCloud), req.notes.single())
    }

    // ---------------------------------------------------------------- 徽标

    private fun state(
        onDevice: Boolean = false,
        missingReason: String = "",
        remotes: List<LocalRemoteEntity> = emptyList(),
        configured: Set<CloudProvider> = CloudProvider.entries.toSet(),
        match: Song? = null,
        platformFallback: Boolean = true,
        device: String = "Pixel 8",
    ) = LocalCopyRules.state(onDevice, missingReason, remotes, configured, match, platformFallback, device, now)

    @Test
    fun badgeFollowsResolveOrder() {
        assertEquals(LocalCopyKind.Device, state(onDevice = true, remotes = listOf(remote(CloudProvider.NeteaseCloud))).kind)
        val cloud = state(remotes = listOf(remote(CloudProvider.WebDav)), match = match)
        assertEquals(LocalCopyKind.Cloud, cloud.kind)
        assertEquals(CloudProvider.WebDav, cloud.provider)
        val platform = state(match = match)
        assertEquals(LocalCopyKind.Platform, platform.kind)
        assertEquals("netease", platform.platform)
        assertEquals(Availability.Playable, platform.status)
    }

    @Test
    fun badgeForNothingAnywhereNamesTheDevice() {
        val none = state()
        assertEquals(LocalCopyKind.None, none.kind)
        assertEquals("只在 Pixel 8 上", none.note)
        assertTrue(none.blocked)
        assertTrue(none.gone)
        assertEquals("只在原来的设备上", state(device = "").note)
        // 设置关了平台版本：有匹配也放不了
        assertEquals(LocalCopyKind.None, state(match = match, platformFallback = false).kind)
        // 这台设备扫到过、文件不在了
        assertEquals("文件不在了", state(missingReason = "文件不在了").note)
    }

    @Test
    fun badgeForCloudTrouble() {
        // 连不上：空心点，可以点（下次会重试）
        val unreachable = state(remotes = listOf(remote(CloudProvider.NeteaseCloud, check = LocalCloudCheck.UNREACHABLE, checkedAt = now - 1_000)))
        assertEquals(LocalCopyKind.CloudTrouble, unreachable.kind)
        assertEquals(Availability.PreviewOnly, unreachable.status)
        assertEquals(CloudMessages.CLOUD_UNREACHABLE, unreachable.note)
        // 退避时间过了就当能试
        assertEquals(
            LocalCopyKind.Cloud,
            state(remotes = listOf(remote(CloudProvider.NeteaseCloud, check = LocalCloudCheck.UNREACHABLE, checkedAt = now - 120_000))).kind,
        )
        // 云端删了：删除线，点了打开面板
        val deleted = state(remotes = listOf(remote(CloudProvider.NeteaseCloud, status = CloudRefStatus.DELETED)))
        assertEquals(Availability.Unavailable, deleted.status)
        assertEquals(CloudMessages.CLOUD_DELETED, deleted.note)
        assertTrue(deleted.blocked)
        // 这台设备没登录网易云：空心点 + 去登录
        val notLoggedIn = state(remotes = listOf(remote(CloudProvider.NeteaseCloud)), configured = emptySet())
        assertEquals(Availability.PreviewOnly, notLoggedIn.status)
        // 云端出问题但有平台版本：照样放平台版本
        assertEquals(LocalCopyKind.Platform, state(remotes = listOf(remote(CloudProvider.NeteaseCloud, status = CloudRefStatus.DELETED)), match = match).kind)
    }

    @Test
    fun checkWindows() {
        assertTrue(LocalCloudCheck.blocks(LocalCloudCheck.MISSING, now - 59 * 60_000, now))
        assertTrue(!LocalCloudCheck.blocks(LocalCloudCheck.MISSING, now - 61 * 60_000, now))
        assertTrue(LocalCloudCheck.blocks(LocalCloudCheck.UNREACHABLE, now - 10_000, now))
        assertTrue(!LocalCloudCheck.blocks(LocalCloudCheck.UNREACHABLE, now - 31_000, now))
        assertTrue(!LocalCloudCheck.blocks(LocalCloudCheck.OK, now, now))
    }
}
