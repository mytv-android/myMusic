package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗登录设备的种子推导：`KUGOU_API_MID` 参与客户端签名，设备必须是确定性的，
 * 否则每次登录都是服务端眼里的一台新设备，会话短、签名也不稳定。
 */
class KugouLoginDeviceTest {

    private val seed = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun sameSeedYieldsSameDevice() {
        assertEquals(kugouLoginDeviceFields(seed), kugouLoginDeviceFields(seed))
    }

    @Test
    fun differentSeedsYieldDifferentDevices() {
        val other = kugouLoginDeviceFields("ffeeddccbbaa99887766554433221100")
        val mine = kugouLoginDeviceFields(seed)
        assertEquals(4, mine.size)
        assertNotEquals(mine, other)
        // 四个字段都得不一样，只 GUID 变了说明盐值没起作用
        listOf("KUGOU_API_GUID", "KUGOU_API_MID", "KUGOU_API_MAC", "KUGOU_API_DEV").forEach { key ->
            assertNotEquals("$key 应该跟着种子变", mine[key], other[key])
        }
    }

    @Test
    fun guidIsUuidShapedAndCarriesTheSeed() {
        val guid = kugouLoginDeviceFields(seed)["KUGOU_API_GUID"].orEmpty()
        assertEquals(36, guid.length)
        assertTrue(guid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        // 第 13 位是版本 4、第 17 位是变体 10：和随机那份同形，服务端不会因形状异常打回
        assertEquals('4', guid[14])
        assertTrue(guid[19] in "89ab")
        // 种子里没被版本/变体位盖掉的部分原样带着（那两个位在第 13 和第 17 个十六进制位）
        val hex = guid.replace("-", "")
        assertEquals(seed.substring(0, 12), hex.substring(0, 12))
        assertEquals(seed.substring(13, 16), hex.substring(13, 16))
        assertEquals(seed.substring(17), hex.substring(17))
    }

    @Test
    fun shortSeedIsPaddedToAFullGuid() {
        val fields = kugouLoginDeviceFields("abc")
        val guid = fields["KUGOU_API_GUID"].orEmpty()
        assertEquals(36, guid.length)
        val hex = guid.replace("-", "")
        assertEquals("abc" + "0".repeat(9), hex.substring(0, 12))
        assertEquals("000", hex.substring(13, 16))
        assertEquals("0".repeat(15), hex.substring(17))
        // 补齐后仍要能算出 mid，不能抛异常
        assertTrue(fields["KUGOU_API_MID"].orEmpty().isNotEmpty())
    }

    @Test
    fun nonHexCharactersInSeedAreDropped() {
        val fields = kugouLoginDeviceFields("a1!b2?c3d4")
        val guid = fields["KUGOU_API_GUID"].orEmpty()
        assertEquals(36, guid.length)
        val hex = guid.replace("-", "")
        assertEquals("a1b2c3d4" + "0".repeat(4), hex.substring(0, 12))
        assertEquals("000", hex.substring(13, 16))
        assertEquals("0".repeat(15), hex.substring(17))
    }

    @Test
    fun midIsTheGuidMd5AsDecimal() {
        val fields = kugouLoginDeviceFields(seed)
        val mid = fields["KUGOU_API_MID"].orEmpty()
        assertTrue("mid 只能是十进制数字：$mid", mid.isNotEmpty() && mid.all { it in '0'..'9' })
        assertEquals(kugouMidOf(fields["KUGOU_API_GUID"].orEmpty()), mid)
    }

    @Test
    fun macAndDevUseTheClientAlphabet() {
        val fields = kugouLoginDeviceFields(seed)
        val mac = fields["KUGOU_API_MAC"].orEmpty()
        val dev = fields["KUGOU_API_DEV"].orEmpty()
        val alphabet = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        assertEquals(12, mac.length)
        assertEquals(16, dev.length)
        assertTrue(mac.all { it in alphabet })
        assertTrue(dev.all { it in alphabet })
        assertNotEquals("MAC 和 DEV 不能是同一条串", mac, dev)
    }

    @Test
    fun derivedDfidKeepsTheRandomShape() {
        // 随机那份是 md5(...).uppercase().take(24)：派生值必须同形，不能改请求形状
        val dfid = kugouSeedDfid(seed)
        assertEquals(24, dfid.length)
        assertTrue(dfid.matches(Regex("[0-9A-F]{24}")))
        assertEquals(dfid, kugouSeedDfid(seed))
        assertNotEquals(dfid, kugouSeedDfid("ffeeddccbbaa99887766554433221100"))
    }

    @Test
    fun derivedGuidKeepsTheRandomGuidShape() {        // 随机那份是标准 UUID v4 形状，推导出来的必须同形，服务端不会因形状异常打回
        val guid = kugouSeedGuid(seed)
        assertEquals(36, guid.length)
        assertEquals(5, guid.split('-').size)
        guid.split('-').forEachIndexed { index, part ->
            assertEquals(listOf(8, 4, 4, 4, 12)[index], part.length)
        }
    }
}
