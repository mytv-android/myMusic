package com.mytv.mymusic

import com.mytv.mymusic.data.native.AppleHlsManifest
import com.mytv.mymusic.data.native.AppleHlsRewrite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * init 段 tenc 的 kid 改写。样例是 2026-11-06 实抓的 NEON（adam 6820428730）init 段。
 *
 * 为什么必须改：Apple 的 `tenc.default_KID` 恒为全零（enhancedHls 和 web 的 lossy 都一样），
 * 而授权回来的密钥带着 PSSH 里那个 kid。安卓 CDM 按样本 kid 严格查表，全零对不上任何密钥，
 * MediaCodec 就报 `requested key has not been loaded: ERROR_DRM_NO_LICENSE`（真机 6006）。
 * 在模拟器（Pixel 等价 Widevine L3）上验证过：不改时播放失败，改完后进度正常前进。
 */
class AppleInitSegmentKidTest {

    private val init: ByteArray = checkNotNull(javaClass.getResourceAsStream("/apple_neon_init.mp4"))
        .use { it.readBytes() }

    /** 真机抓到的轮换 key PSSH（kid = W7vSU2MyMi…）。 */
    private val pssh =
        "data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAABbu9JTYzIyICAgICBI88aJmwY="

    private fun tencKids(bytes: ByteArray): List<String> {
        val marker = "tenc".toByteArray()
        val out = mutableListOf<String>()
        var i = 0
        while (i <= bytes.size - 4) {
            if (marker.indices.all { bytes[i + it] == marker[it] }) {
                out += bytes.copyOfRange(i + 12, i + 28).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }
            i++
        }
        return out
    }

    @Test
    fun `real init segment has all-zero kids`() {
        val kids = tencKids(init)
        assertEquals("样例应当有两个 tenc（预取条目 + 正片条目）", 2, kids.size)
        kids.forEach { assertEquals("00000000000000000000000000000000", it) }
    }

    @Test
    fun `key id is read out of the pssh`() {
        val kid = AppleHlsManifest.widevineKeyId(pssh)
        assertNotNull("PSSH 里没取到 kid", kid)
        assertEquals(
            "000000005bbbd2536332322020202020",
            kid!!.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }

    @Test
    fun `patching rewrites every tenc kid and leaves the rest untouched`() {
        val kid = AppleHlsManifest.widevineKeyId(pssh)!!
        val patched = AppleHlsRewrite.patchInitSegmentKid(init, kid)
        assertEquals("长度必须不变（盒子偏移全靠它）", init.size, patched.size)
        val expected = kid.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        tencKids(patched).forEach { assertEquals(expected, it) }
        // 除了 kid 那 32 字节，别的一个字节都不能变
        var changed = 0
        for (i in init.indices) {
            if (init[i] != patched[i]) {
                changed++
                val offsetInTenc = i
                assertTrue("改到了 tenc kid 之外的位置: $offsetInTenc", isInsideTencKid(init, i))
            }
        }
        // kid 前 4 字节本来就是 0，不会变；实际改动数 = 每个 kid 的非零字节数 × 2
        val nonZero = kid.count { it != 0.toByte() }
        assertEquals("改动字节数应恰好是两个 kid 的非零字节", nonZero * 2, changed)
    }

    /** 判断某个下标是否落在任一 tenc 的 16 字节 kid 区间内。 */
    private fun isInsideTencKid(bytes: ByteArray, index: Int): Boolean {
        val marker = "tenc".toByteArray()
        var i = 0
        while (i <= bytes.size - 4) {
            if (marker.indices.all { bytes[i + it] == marker[it] }) {
                if (index in (i + 12) until (i + 28)) return true
            }
            i++
        }
        return false
    }

    @Test
    fun `junk input is returned unchanged`() {
        val kid = ByteArray(16) { 1 }
        assertArrayEquals(ByteArray(0), AppleHlsRewrite.patchInitSegmentKid(ByteArray(0), kid))
        val junk = ByteArray(64) { 7 }
        assertArrayEquals(junk, AppleHlsRewrite.patchInitSegmentKid(junk, kid))
        // kid 长度不对就原样返回，避免把 init 段写坏
        assertArrayEquals(init, AppleHlsRewrite.patchInitSegmentKid(init, ByteArray(8)))
    }

    @Test
    fun `pssh without key id yields null`() {
        assertNull(AppleHlsManifest.widevineKeyId(""))
        assertNull(AppleHlsManifest.widevineKeyId("data:text/plain;base64,"))
        assertNull(AppleHlsManifest.widevineKeyId("data:text/plain;base64,AAAA"))
        assertNull(AppleHlsManifest.widevineKeyId("not a data uri"))
    }
}
