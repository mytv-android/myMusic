package com.mytv.mymusic.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * localId 是跨设备对歌的依据，算法一变所有设备的歌单都对不上。这里用 Python hashlib 独立算出的值钉死：
 * sha256(前 min(64K,size) ‖ 末 min(64K,size) ‖ uint64 小端 size)。
 */
class LocalIdentityTest {
    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun knownVectorForLargeFile() {
        assertEquals(
            "sha256:d6b68c4828becb98a1e903a6f2be899a846817471e47216a5df1ef58c0ce7997",
            LocalIdentity.of(pattern(200_000)),
        )
    }

    @Test
    fun filesUnder128KbOverlapHeadAndTail() {
        // 10 万字节：首尾两段各 64KB，中间重叠；照样各读一遍
        assertEquals(
            "sha256:ff1262feddb4e1f210956e6206338e59af920709cec77416324ce9ee5603eb3f",
            LocalIdentity.of(pattern(100_000)),
        )
        // 比一段还短：首尾都是整个文件
        assertEquals(
            "sha256:07466e8eabd508bdfadf53b67a2f586a94fea55e69bece3bbdc7479f25d4d05e",
            LocalIdentity.of("hello".toByteArray()),
        )
        assertEquals(
            "sha256:af5570f5a1810b7af78caf4bc70a660f0df51e42baf91d4de5b2328de0e83dfc",
            LocalIdentity.of(ByteArray(0)),
        )
    }

    @Test
    fun sizeIsLittleEndianUint64() {
        assertArrayEquals(byteArrayOf(0x40, 0x0D, 0x03, 0, 0, 0, 0, 0), LocalIdentity.sizeBytes(200_000))
        assertArrayEquals(ByteArray(8), LocalIdentity.sizeBytes(0))
    }

    @Test
    fun sameHeadAndTailButDifferentSizeDiffer() {
        val a = pattern(300_000)
        val b = a.copyOfRange(0, 65_536) + ByteArray(50_000) + a.copyOfRange(a.size - 65_536, a.size)
        assertNotEquals(LocalIdentity.of(a), LocalIdentity.of(b))
    }

    @Test
    fun middleBytesDoNotMatter() {
        val a = pattern(300_000)
        val b = a.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        assertEquals(LocalIdentity.of(a), LocalIdentity.of(b))
    }

    /** 管道式的文档提供方只能顺序读：每次 read 只给一点、skip 返回 0，结果要和随机读一致。 */
    private class Trickle(input: InputStream) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 777))
        override fun skip(n: Long): Long = 0
    }

    @Test
    fun streamFallbackMatchesRandomAccess() {
        for (size in listOf(0, 5, 65_535, 65_536, 65_537, 100_000, 131_072, 131_073, 200_000, 1_000_003)) {
            val bytes = pattern(size)
            assertEquals("size=$size", LocalIdentity.of(bytes), LocalIdentity.hashStream(ByteArrayInputStream(bytes), size.toLong()))
            assertEquals(
                "trickle size=$size",
                LocalIdentity.of(bytes),
                LocalIdentity.hashStream(Trickle(ByteArrayInputStream(bytes)), size.toLong()),
            )
        }
    }

    @Test
    fun shortForm() {
        val id = LocalIdentity.of("hello".toByteArray())
        assertEquals("sha256:0746…d05e", LocalIdentity.short(id))
        assertTrue(id.startsWith(LocalIdentity.PREFIX))
        assertEquals(LocalIdentity.PREFIX.length + 64, id.length)
    }
}
