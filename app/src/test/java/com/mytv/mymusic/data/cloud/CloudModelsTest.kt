package com.mytv.mymusic.data.cloud

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用模型：容量差额、预检、文案里的大小、扩展名、CloudRef 序列化。 */
class CloudModelsTest {
    @Test
    fun quotaShortfallAndUnknownTotal() {
        val quota = CloudQuota(used = 90, total = 100)
        assertTrue(quota.known)
        assertEquals(10L, quota.free)
        assertEquals(0L, quota.shortfall(10))
        assertEquals(5L, quota.shortfall(15))
        // 总量不知道（WebDAV 常见）：不拦
        assertEquals(0L, CloudQuota(used = 90, total = 0).shortfall(Long.MAX_VALUE))
        // 已经超用也不会算出负的剩余
        assertEquals(0L, CloudQuota(used = 120, total = 100).free)
    }

    @Test
    fun precheckSkipsOversizedAndSumsTheRest() {
        val plan = UploadPrecheck.plan(
            listOf(CloudUploadCandidate("a", 400), CloudUploadCandidate("b", 600), CloudUploadCandidate("c", 50)),
            CloudQuota(used = 0, total = 500),
            maxFileSize = 500,
        )
        assertEquals(listOf("a", "c"), plan.accepted)
        assertEquals(listOf("b"), plan.tooLarge)
        assertEquals(450L, plan.totalBytes)
        assertEquals(0L, plan.shortfall)
        assertTrue(plan.canStart)

        val noQuota = UploadPrecheck.plan(listOf(CloudUploadCandidate("a", 1L shl 40)), null, null)
        assertTrue(noQuota.canStart)
    }

    @Test
    fun messagesFormatSizes() {
        assertEquals("1.2 GB", CloudMessages.formatSize((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2 GB", CloudMessages.formatSize(2L * 1024 * 1024 * 1024))
        assertEquals("412 MB", CloudMessages.formatSize(412L * 1024 * 1024))
        assertEquals("36 KB", CloudMessages.formatSize(36L * 1024))
        assertEquals("云盘空间不够，还差 1.2 GB", CloudMessages.quotaExceeded((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("单个文件超过 500 MB，云盘不收，已跳过", CloudMessages.fileTooLarge(500L * 1024 * 1024))
    }

    @Test
    fun extensionAndBaseName() {
        assertEquals("flac", cloudExtensionOf("雨季备忘.FLAC"))
        assertEquals("", cloudExtensionOf("README"))
        assertEquals("", cloudExtensionOf(".hidden"))
        assertEquals("mp3", cloudExtensionOf("a/b.c/d.mp3"))
        assertEquals("雨季备忘", cloudBaseNameOf("雨季备忘.flac"))
        assertEquals("a.b", cloudBaseNameOf("a.b.mp3"))
    }

    @Test
    fun cloudRefRoundTripsAndParsesTimestamp() {
        val ref = CloudRef(
            provider = CloudProvider.NeteaseCloud.id,
            id = "999",
            fileName = "雨季备忘.flac",
            fileSize = 10,
            uploadedAt = CloudRef.timestamp(1_762_689_540_000),
            md5 = "abc",
            cloudSongId = "888",
            localId = "sha256:9f",
        )
        assertEquals("2025-11-09T11:59:00Z", ref.uploadedAt)
        assertEquals(1_762_689_540_000, ref.uploadedAtMillis())
        val json = Json.encodeToString(CloudRef.serializer(), ref)
        assertEquals(ref, Json.decodeFromString(CloudRef.serializer(), json))
        assertEquals(CloudProvider.NeteaseCloud, ref.cloudProvider)
        assertTrue(ref.isReady)
        assertFalse(ref.copy(status = CloudRefStatus.MISSING).isReady)
        assertEquals(0L, ref.copy(uploadedAt = "garbage").uploadedAtMillis())
    }

    @Test
    fun uploadSourceOpensAtOffset() {
        val source = CloudUploadSource.of("a.mp3", "0123456789".toByteArray())
        assertEquals("456789", source.open(4).readBytes().toString(Charsets.UTF_8))
        assertEquals("10", source.version)
    }
}
