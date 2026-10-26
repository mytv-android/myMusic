package com.mytv.mymusic

import com.mytv.mymusic.ui.settings.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关于页上的主页与 Telegram 二维码由本机编码器生成，扫不出来就等于没有这两个入口。
 * 这里校验定位图形、时序图形和模块排布；换链接时先确认改完还能过。
 */
class QrCodeTest {
    @Test
    fun optimizedPenaltyPreservesExistingMatrices() {
        val fixtures = mapOf(
            "https://github.com/mytv-android/myMusic" to "6fb0dd06e73e64d5c3fbcca1e9138f6a71ff6237e05f985ec64d993b197098b8",
            "https://t.me/mytv_android_channel" to "c14e23b309043963578e82f21d12f92b53e285af48046d33435d259fad62c465",
            ("https://example.test/login?token=" + "abcdefghij".repeat(120)) to "e4de7fe3374d58396dab258a701414814fa68bb78060d6a5b3cd008292ca8814",
        )
        fixtures.forEach { (text, expected) ->
            val matrix = requireNotNull(QrEncoder.encode(text))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            matrix.forEach { row -> row.forEach { digest.update(if (it) 1.toByte() else 0.toByte()) } }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun encodesAboutLinks() {
        val links = listOf(
            "https://github.com/mytv-android/myMusic",
            "https://t.me/mytv_android_channel",
        )
        links.forEach { url ->
            val matrix = QrEncoder.encode(url)
            assertNotNull(url, matrix)
            val size = matrix!!.size
            // 版本 1–40 对应 21–177 个模块，且边长必须是 4n+17
            assertTrue("非法尺寸 $size", size in 21..177 && (size - 17) % 4 == 0)

            // 三个角的定位图形：外框深、中间一圈浅、中心 3×3 深
            for ((x, y) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
                for (dy in 0 until 7) for (dx in 0 until 7) {
                    val ring = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
                    assertEquals("定位图形 ($x,$y) 内 $dx,$dy", ring != 2, matrix[y + dy][x + dx])
                }
                // 定位图形外面一圈是静区；越界的那几格本来就不属于二维码
                for (dy in -1 until 8) for (dx in -1 until 8) {
                    val xx = x + dx
                    val yy = y + dy
                    val onEdge = dx == -1 || dx == 7 || dy == -1 || dy == 7
                    if (onEdge && xx in 0 until size && yy in 0 until size) {
                        assertEquals("定位图形外静区 $xx,$yy", false, matrix[yy][xx])
                    }
                }
            }

            // 时序图形：两个定位图形之间的第 6 行 / 第 6 列深浅交替
            for (i in 8 until size - 8) {
                assertEquals("时序图形 第6行 $i", i % 2 == 0, matrix[6][i])
                assertEquals("时序图形 第6列 $i", i % 2 == 0, matrix[i][6])
            }
            // 解码器靠这个固定深色模块定向
            assertTrue("深色模块", matrix[size - 8][8])
        }
    }
}
