package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续传判断的纯函数，逐条对齐 libresoda `resume.rs` 的用例集：
 * 200 一律重写、密钥变了重下、416 的「范围未知」形态才判下完、206 必须正好接上本地字节。
 */
class SodaResumeDownloadTest {

    @Test
    fun fullResponseAlwaysRewrites() {
        // 200 最权威，密钥变没变都不看
        assertEquals(
            SodaResumeAction.Rewrite,
            sodaDecideResume(200, null, 0L, 0L, keyChanged = false),
        )
        assertEquals(
            SodaResumeAction.Rewrite,
            sodaDecideResume(200, null, 4096L, 1000L, keyChanged = true),
        )
    }

    @Test
    fun keyChangeForcesRefetch() {
        assertEquals(
            SodaResumeAction.Refetch,
            sodaDecideResume(206, SodaRangeInfo(4096L, 9999L, 10000L), 4096L, 10000L, keyChanged = true),
        )
    }

    @Test
    fun unknownRangeOn416MeansComplete() {
        val range = SodaRangeInfo(first = null, last = null, total = 4096L)
        assertEquals(
            SodaResumeAction.Complete,
            sodaDecideResume(416, range, localLen = 4096L, recordedTotal = 4096L, keyChanged = false),
        )
    }

    @Test
    fun closedRangeOn416IsNotComplete() {
        // 416 给了闭合区间说明服务端不认这个偏移，重下
        val range = SodaRangeInfo(first = 0L, last = 4095L, total = 4096L)
        assertEquals(
            SodaResumeAction.Refetch,
            sodaDecideResume(416, range, localLen = 4096L, recordedTotal = 4096L, keyChanged = false),
        )
    }

    @Test
    fun totalMismatchOn416IsNotComplete() {
        val range = SodaRangeInfo(first = null, last = null, total = 5000L)
        assertEquals(
            SodaResumeAction.Refetch,
            sodaDecideResume(416, range, localLen = 4096L, recordedTotal = 4096L, keyChanged = false),
        )
    }

    @Test
    fun partialContentAppendsOnlyWhenItLinesUp() {
        val range = SodaRangeInfo(first = 4096L, last = 9999L, total = 10000L)
        assertEquals(
            SodaResumeAction.Append,
            sodaDecideResume(206, range, localLen = 4096L, recordedTotal = 10000L, keyChanged = false),
        )
        // 首字节对不上本地长度：服务端给了别的东西，重下
        assertEquals(
            SodaResumeAction.Refetch,
            sodaDecideResume(206, SodaRangeInfo(0L, 9999L, 10000L), localLen = 4096L, recordedTotal = 10000L, keyChanged = false),
        )
        // 总长和记录不一致：中间被人改过，重下
        assertEquals(
            SodaResumeAction.Refetch,
            sodaDecideResume(206, range, localLen = 4096L, recordedTotal = 12000L, keyChanged = false),
        )
    }

    @Test
    fun anythingElseRefetches() {
        assertEquals(
            SodaResumeAction.Refetch,
            sodaDecideResume(404, null, localLen = 0L, recordedTotal = 0L, keyChanged = false),
        )
        assertEquals(
            SodaResumeAction.Refetch,
            sodaDecideResume(500, null, localLen = 100L, recordedTotal = 1000L, keyChanged = false),
        )
    }

    @Test
    fun contentRangeParsing() {
        assertEquals(
            SodaRangeInfo(4096L, 9999L, 10000L),
            sodaParseContentRange("bytes 4096-9999/10000"),
        )
        // 大小写不敏感的单位
        assertEquals(
            SodaRangeInfo(0L, 99L, 100L),
            sodaParseContentRange("BYTES 0-99/100"),
        )
        // 416 的未知范围形态
        assertEquals(
            SodaRangeInfo(null, null, 4096L),
            sodaParseContentRange("bytes */4096"),
        )
    }

    @Test
    fun contentRangeRejectsGarbage() {
        assertNull(sodaParseContentRange(null))
        assertNull(sodaParseContentRange(""))
        assertNull(sodaParseContentRange("bytes 0-99"))
        assertNull(sodaParseContentRange("items 0-99/100"))
        // last < first
        assertNull(sodaParseContentRange("bytes 99-0/100"))
        // total 必须大于 last
        assertNull(sodaParseContentRange("bytes 0-100/100"))
        assertNull(sodaParseContentRange("bytes a-b/c"))
    }

    @Test
    fun partPathsAppendSuffixesToWholeName() {
        // 后缀拼在整个文件名后面，扩展名不动（Windows 上路径分隔符不同，只比文件名）
        val paths = SodaPartPaths(java.io.File("/tmp/soda-x.m4a"))
        assertEquals("soda-x.m4a.part", paths.part.name)
        assertEquals("soda-x.m4a.part.meta", paths.meta.name)
    }

    @Test
    fun trustedRecordRejectsEveryMismatch() {
        val dir = java.nio.file.Files.createTempDirectory("soda-part").toFile()
        try {
            val target = java.io.File(dir, "song.m4a")
            val paths = SodaPartPaths(target)
            // 没有 .part：没有记录
            assertNull(paths.trustedRecord("fp"))

            paths.part.writeBytes(ByteArray(4096))
            paths.writeMeta(10000L, 4096L, "fp")
            assertEquals(4096L, paths.trustedRecord("fp")?.writtenBytes)

            // 密钥指纹不一致 → 作废
            assertNull(paths.trustedRecord("other"))
            // 记录的字节数和实际对不上 → 作废
            paths.writeMeta(10000L, 2048L, "fp")
            assertNull(paths.trustedRecord("fp"))
            // 记录的总长小于本地字节 → 作废
            paths.writeMeta(1024L, 4096L, "fp")
            assertNull(paths.trustedRecord("fp"))

            // 0 字节的 .part 刻意不算：否则 416 的“总长 0”会把空文件当完整交付
            paths.part.writeBytes(ByteArray(0))
            paths.writeMeta(0L, 0L, "fp")
            assertNull(paths.trustedRecord("fp"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun metaRoundTripsThroughJson() {
        val dir = java.nio.file.Files.createTempDirectory("soda-meta").toFile()
        try {
            val paths = SodaPartPaths(java.io.File(dir, "song.m4a"))
            // 记录的 written_bytes 必须和 .part 的实际长度一致，否则整份作废
            paths.part.writeBytes(ByteArray(6789))
            paths.writeMeta(12345L, 6789L, "abc123")
            val record = paths.trustedRecord("abc123")
            assertEquals(12345L, record?.totalLen)
            assertEquals(6789L, record?.writtenBytes)
            assertEquals("abc123", record?.keyFingerprint)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun metaJsonEscapesQuotes() {
        val body = sodaPartMetaJson(1L, 2L, "a\"b")
        // 引号要转义，否则记录文件解析不了
        assertTrue(body.contains("\"key_fingerprint\":\"a\\\"b\""))
        // 四个字段齐全，etag 留空（本地没有可信的服务端标识）
        assertTrue(body.contains("\"total_len\":1"))
        assertTrue(body.contains("\"written_bytes\":2"))
        assertTrue(body.contains("\"etag\":\"\""))
    }

    @Test
    fun downloadResultComparesByContent() {
        val a = SodaDownloadResult(SodaDownloadReport(transferred = 5L), byteArrayOf(1, 2, 3))
        val b = SodaDownloadResult(SodaDownloadReport(transferred = 5L), byteArrayOf(1, 2, 3))
        val c = SodaDownloadResult(SodaDownloadReport(transferred = 5L), byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
