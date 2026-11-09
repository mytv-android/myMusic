package com.mytv.mymusic.data.local

import com.mytv.mymusic.data.Song
import com.mytv.mymusic.data.cloud.CloudErrorKind
import com.mytv.mymusic.data.cloud.CloudException
import com.mytv.mymusic.data.cloud.CloudMessages
import com.mytv.mymusic.data.cloud.CloudProvider
import com.mytv.mymusic.data.cloud.CloudQueueItem
import com.mytv.mymusic.data.cloud.CloudQueueState
import com.mytv.mymusic.data.cloud.CloudQuota
import com.mytv.mymusic.data.cloud.CloudRefStatus
import com.mytv.mymusic.data.cloud.CloudUploadCandidate
import com.mytv.mymusic.data.cloud.CloudUploadMeta
import com.mytv.mymusic.data.cloud.CloudUploadProgress
import com.mytv.mymusic.data.cloud.CloudUploadSource
import com.mytv.mymusic.data.cloud.CloudUploadStage
import com.mytv.mymusic.data.cloud.UploadPrecheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上传的规则：落盘往返、去重、预检的说明、默认位置、加进歌单时的提示。 */
class LocalUploadRulesTest {
    private val mb = 1024L * 1024

    /** 只登记大小、不真读：落盘和预检都只看大小。 */
    private fun source(name: String, size: Long) = object : CloudUploadSource {
        override val fileName: String = name
        override val size: Long = size
        override fun open(offset: Long) = ByteArray(0).inputStream()
    }

    private fun item(id: String, stage: CloudUploadStage = CloudUploadStage.QUEUED, error: CloudException? = null, attempts: Int = 0) =
        CloudQueueItem(
            localId = id,
            source = source("$id.flac", 30 * mb),
            meta = CloudUploadMeta(localId = id, title = id),
            progress = CloudUploadProgress(stage, 0, 30 * mb, error = error),
            attempts = attempts,
        )

    private fun remote(id: String, provider: CloudProvider, status: String = CloudRefStatus.READY, check: String = LocalCloudCheck.OK) =
        LocalRemoteEntity(localId = id, provider = provider.id, remoteId = "r-$id", status = status, checkState = check)

    // ---------- 落盘往返 ----------

    @Test
    fun queueRowsRoundTripKeepsOrderStateAndErrors() {
        val risk = CloudException(CloudErrorKind.RiskControl, CloudMessages.RISK_CONTROL, CloudProvider.NeteaseCloud, code = -460)
        val state = CloudQueueState(
            items = listOf(
                item("sha256:a", CloudUploadStage.QUEUED, error = risk, attempts = 2),
                item("sha256:done", CloudUploadStage.READY, attempts = 1),
                item("sha256:b", CloudUploadStage.UPLOADING, attempts = 1),
                item("sha256:c", CloudUploadStage.FAILED, CloudException(CloudErrorKind.TranscodeTimeout, CloudMessages.TRANSCODE_TIMEOUT), 3),
                item("sha256:d"),
            ),
            paused = true,
            pauseReason = risk,
        )
        val rows = LocalUploadRows.toRows(CloudProvider.NeteaseCloud, state)
        // 传完的不存；正在传的记成排队；位置连续
        assertEquals(listOf("sha256:a", "sha256:b", "sha256:c", "sha256:d"), rows.map { it.localId })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.position })
        assertEquals(
            listOf(LocalUploadRows.QUEUED, LocalUploadRows.QUEUED, LocalUploadRows.FAILED, LocalUploadRows.QUEUED),
            rows.map { it.state },
        )

        // 打乱行序也按 position 放回；别的云端的行不混进来
        val mixed = rows.shuffled() + LocalUploadEntity("sha256:x", CloudProvider.WebDav.id, 0, LocalUploadRows.QUEUED)
        val (items, pausedBy) = LocalUploadRows.restore(
            CloudProvider.NeteaseCloud,
            mixed,
            source = { id -> source("$id.flac", 30 * mb) to CloudUploadMeta(localId = id) },
            missing = { error("不该走到这里") },
        )
        assertEquals(listOf("sha256:a", "sha256:b", "sha256:c", "sha256:d"), items.map { it.localId })
        assertEquals(
            listOf(CloudUploadStage.QUEUED, CloudUploadStage.QUEUED, CloudUploadStage.FAILED, CloudUploadStage.QUEUED),
            items.map { it.stage },
        )
        assertEquals(listOf(2, 1, 3, 0), items.map { it.attempts })
        assertEquals(CloudErrorKind.TranscodeTimeout, items[2].error?.kind)
        assertEquals(CloudMessages.TRANSCODE_TIMEOUT, items[2].error?.message)
        // 排在最前的排队项挂着风控：重启后整个队列还是暂停，等用户点继续
        assertEquals(CloudErrorKind.RiskControl, pausedBy?.kind)
        assertEquals(-460, pausedBy?.code)

        // 再存一次得到同样的行：往返稳定，不会每次启动都改写
        val again = LocalUploadRows.toRows(CloudProvider.NeteaseCloud, CloudQueueState(items = items))
        assertEquals(rows, again)
    }

    @Test
    fun restoreMarksMissingFilesFailedInsteadOfDropping() {
        val rows = listOf(
            LocalUploadEntity("sha256:gone", CloudProvider.WebDav.id, 0, LocalUploadRows.QUEUED),
            LocalUploadEntity("sha256:here", CloudProvider.WebDav.id, 1, LocalUploadRows.QUEUED),
        )
        val (items, pausedBy) = LocalUploadRows.restore(
            CloudProvider.WebDav,
            rows,
            source = { id -> if (id == "sha256:here") source("here.flac", mb) to CloudUploadMeta(localId = id) else null },
            missing = { id -> source("gone.flac", 0) to CloudUploadMeta(localId = id) },
        )
        assertEquals(CloudUploadStage.FAILED, items[0].stage)
        assertEquals(LocalUploadRows.FILE_GONE, items[0].error?.message)
        assertEquals(CloudUploadStage.QUEUED, items[1].stage)
        assertNull("没有暂停原因就不暂停", pausedBy)
    }

    // ---------- 去重 ----------

    @Test
    fun planSkipsReadyRemoteAtThatProviderUnlessForced() {
        val remotes = mapOf(
            "a" to listOf(remote("a", CloudProvider.NeteaseCloud)),
            // 上次取流发现云端被删了：不算“已经有一份”，可以再传
            "b" to listOf(remote("b", CloudProvider.NeteaseCloud, check = LocalCloudCheck.MISSING)),
            // 在 WebDAV 上，不影响传到网易云盘
            "c" to listOf(remote("c", CloudProvider.WebDav)),
            "d" to listOf(remote("d", CloudProvider.NeteaseCloud, status = CloudRefStatus.DELETED)),
        )
        val ids = listOf("a", "b", "c", "d", "e", "f", "a", "g")
        val plan = LocalUploadRules.plan(
            ids = ids,
            provider = CloudProvider.NeteaseCloud,
            remotes = remotes,
            onDevice = { it != "f" },
            queued = setOf("g"),
        )
        assertEquals(listOf("b", "c", "d", "e"), plan.upload)
        assertEquals(listOf("a"), plan.alreadyUploaded)
        assertEquals(listOf("f"), plan.notOnDevice)
        assertEquals(listOf("g"), plan.alreadyQueued)

        // 用户在云端副本那行明确点了“重新上传”
        val forced = LocalUploadRules.plan(listOf("a"), CloudProvider.NeteaseCloud, remotes, { true }, emptySet(), force = true)
        assertEquals(listOf("a"), forced.upload)
    }

    // ---------- 预检的说明 ----------

    @Test
    fun noticeExplainsShortfallTooLargeAndAlreadyUploaded() {
        val plan = UploadPlan(upload = listOf("a", "b", "c", "big"), alreadyUploaded = listOf("x"), notOnDevice = emptyList(), alreadyQueued = emptyList())
        // 剩 1 GB；三首能收的加起来 1224 MB，超限的那首不算进去
        val quota = CloudQuota(used = 59L * 1024 * mb, total = 60L * 1024 * mb)
        val short = UploadPrecheck.plan(
            listOf(
                CloudUploadCandidate("a", 500 * mb),
                CloudUploadCandidate("b", 500 * mb),
                CloudUploadCandidate("c", 224 * mb),
                CloudUploadCandidate("big", 600 * mb),
            ),
            quota,
            500 * mb,
        )
        val notice = LocalUploadRules.notice(CloudProvider.NeteaseCloud, plan, short)
        assertEquals("网易云盘空间不够，还差 200 MB", notice.blocking)
        assertTrue(notice.notes.contains("1 首已经在网易云盘里，不重复上传"))
        assertTrue(notice.notes.contains("有 1 首超过 500 MB，会跳过"))

        val fits = UploadPrecheck.plan(listOf(CloudUploadCandidate("a", 10 * mb)), quota, 500 * mb)
        assertEquals("", LocalUploadRules.notice(CloudProvider.NeteaseCloud, plan.copy(upload = listOf("a")), fits).blocking)

        val allTooLarge = UploadPrecheck.plan(listOf(CloudUploadCandidate("big", 600 * mb)), quota, 500 * mb)
        assertEquals("选中的歌都超过 500 MB，网易云盘不收", LocalUploadRules.notice(CloudProvider.NeteaseCloud, plan.copy(upload = listOf("big")), allTooLarge).blocking)
    }

    @Test
    fun noticeWhenNothingToUpload() {
        val uploaded = UploadPlan(emptyList(), listOf("a"), emptyList(), emptyList())
        assertEquals("选中的歌都已经在 WebDAV 里了", LocalUploadRules.notice(CloudProvider.WebDav, uploaded, null).blocking)
        val queued = UploadPlan(emptyList(), emptyList(), emptyList(), listOf("a"))
        assertEquals("选中的歌都已经在上传队列里了", LocalUploadRules.notice(CloudProvider.WebDav, queued, null).blocking)
        val absent = UploadPlan(emptyList(), emptyList(), listOf("a"), emptyList())
        val notice = LocalUploadRules.notice(CloudProvider.WebDav, absent, null)
        assertEquals("没有能上传的歌", notice.blocking)
        assertEquals(listOf("1 首这台设备上没有文件，传不了"), notice.notes)
        // 还没查完容量时不挡（按钮另外等预检结果）
        assertEquals("", LocalUploadRules.notice(CloudProvider.WebDav, UploadPlan(listOf("a"), emptyList(), emptyList(), emptyList()), null).blocking)
    }

    @Test
    fun defaultTargetPrefersChoiceThenNeteaseThenWebDavNeverQq() {
        val both = setOf(CloudProvider.NeteaseCloud, CloudProvider.WebDav, CloudProvider.QqCloud)
        assertEquals(CloudProvider.WebDav, LocalUploadRules.defaultTarget("webdav", both))
        assertEquals(CloudProvider.NeteaseCloud, LocalUploadRules.defaultTarget("", both))
        assertEquals(CloudProvider.WebDav, LocalUploadRules.defaultTarget("netease_cloud", setOf(CloudProvider.WebDav)))
        assertEquals(CloudProvider.NeteaseCloud, LocalUploadRules.defaultTarget("qq_cloud", both))
        assertEquals(CloudProvider.NeteaseCloud, LocalUploadRules.defaultTarget("", emptySet()))
    }

    // ---------- 加进歌单时的提示 ----------

    private fun local(id: String) = Song(id = id, source = LOCAL_SOURCE, name = id)

    @Test
    fun promptOnlyForLocalOnlySongsWhenSyncedAndEnabled() {
        val songs = listOf(local("a"), local("b"), local("c"), Song(id = "1", source = "netease"), local("a"))
        val onDevice = { id: String -> id != "c" }
        val ready = { id: String -> id == "b" }
        assertEquals(listOf("a"), LocalUploadRules.promptFor(songs, enabled = true, syncConnected = true, onDevice = onDevice, hasReadyCopy = ready))
        assertTrue("设置关了不提示", LocalUploadRules.promptFor(songs, false, true, onDevice, ready).isEmpty())
        assertTrue("没连同步不提示：歌单本来就只在这台设备上", LocalUploadRules.promptFor(songs, true, false, onDevice, ready).isEmpty())
        assertTrue("平台歌不提示", LocalUploadRules.promptFor(listOf(Song(id = "1", source = "qq")), true, true, { true }, { false }).isEmpty())
    }

    @Test
    fun promptTextMatchesDesign() {
        assertEquals("已加入「通勤路上」。这首只在本机，换台设备放不了", LocalUploadRules.promptText("已加入「通勤路上」", 1, 1))
        assertEquals("已把 3 首加入「老歌」。这 3 首只在本机，换台设备放不了", LocalUploadRules.promptText("已把 3 首加入「老歌」", 3, 3))
        assertEquals("已把 3 首加入「老歌」。其中 2 首只在本机，换台设备放不了", LocalUploadRules.promptText("已把 3 首加入「老歌」", 3, 2))
    }

    @Test
    fun pausedTextNamesTheReason() {
        val risk = CloudException(CloudErrorKind.RiskControl, CloudMessages.RISK_CONTROL, CloudProvider.NeteaseCloud)
        assertEquals("上传暂停：网易云要求重新验证", LocalUploadRules.pausedText(CloudProvider.NeteaseCloud, risk))
        val webdav = CloudException(CloudErrorKind.Unauthorized, CloudMessages.WEBDAV_UNAUTHORIZED, CloudProvider.WebDav)
        assertEquals("上传暂停：WebDAV 密码不对或已失效", LocalUploadRules.pausedText(CloudProvider.WebDav, webdav))
    }

    @Test
    fun readyCopyIgnoresDeletedAndMissing() {
        assertTrue(LocalUploadRules.hasAnyReadyCopy(listOf(remote("a", CloudProvider.WebDav))))
        assertFalse(LocalUploadRules.hasAnyReadyCopy(listOf(remote("a", CloudProvider.WebDav, status = CloudRefStatus.DELETED))))
        assertFalse(LocalUploadRules.hasAnyReadyCopy(listOf(remote("a", CloudProvider.WebDav, check = LocalCloudCheck.MISSING))))
    }
}
