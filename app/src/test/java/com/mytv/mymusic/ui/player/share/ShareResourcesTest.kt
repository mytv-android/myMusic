package com.mytv.mymusic.ui.player.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * 歌词分享卡片靠的资源，防止以后被误改：
 * - roboto_flex.ttf 要留着 wght / wdth / slnt 三个轴（竖排的斜体、乱排的字宽、粗糙的窄字都靠它），又被削成单轴就全失效；
 *   字体只含拉丁字符，中文走系统回落字体。
 */
class ShareResourcesTest {

    @Test
    fun robotoFlexKeepsWeightWidthAndSlantAxes() {
        val font = Font(locate("src/main/res/font/roboto_flex.ttf").readBytes())
        val axes = font.axes().associateBy { it.tag }
        assertEquals(setOf("wght", "wdth", "slnt"), axes.keys)
        assertAxis(axes.getValue("wght"), 100f, 400f, 1000f)
        assertAxis(axes.getValue("wdth"), 25f, 100f, 151f)
        assertAxis(axes.getValue("slnt"), -10f, 0f, 0f)
    }

    @Test
    fun robotoFlexIsLatinOnly() {
        val font = Font(locate("src/main/res/font/roboto_flex.ttf").readBytes())
        assertTrue("A 要在字体里", font.hasGlyph('A'.code))
        assertTrue("… 要在字体里", font.hasGlyph(0x2026))
        assertFalse("中文不在子集里，走系统回落字体", font.hasGlyph('中'.code))
    }

    private fun assertAxis(axis: Axis, min: Float, default: Float, max: Float) {
        assertEquals("${axis.tag} min", min, axis.min, 0.01f)
        assertEquals("${axis.tag} default", default, axis.default, 0.01f)
        assertEquals("${axis.tag} max", max, axis.max, 0.01f)
    }

    private data class Axis(val tag: String, val min: Float, val default: Float, val max: Float)

    /** 只读表目录、fvar 和 cmap 格式 4，够测试用；不引 fontTools。 */
    private class Font(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes)
        private val tables: Map<String, Int> = buildMap {
            val count = buffer.getShort(4).toInt() and 0xFFFF
            repeat(count) { i ->
                val at = 12 + i * 16
                put(tag(at), buffer.getInt(at + 8))
            }
        }

        private fun tag(at: Int) = String(ByteArray(4) { buffer.get(at + it) }, Charsets.ISO_8859_1)
        private fun u16(at: Int) = buffer.getShort(at).toInt() and 0xFFFF
        private fun fixed(at: Int) = buffer.getInt(at) / 65536f

        fun axes(): List<Axis> {
            val fvar = tables["fvar"] ?: return emptyList()
            val first = fvar + u16(fvar + 4)
            val count = u16(fvar + 8)
            val size = u16(fvar + 10)
            return List(count) { i ->
                val at = first + i * size
                Axis(tag(at), fixed(at + 4), fixed(at + 8), fixed(at + 12))
            }
        }

        fun hasGlyph(codePoint: Int): Boolean {
            val cmap = tables.getValue("cmap")
            val count = u16(cmap + 2)
            val sub = (0 until count).map { cmap + 4 + it * 8 }
                .firstOrNull { u16(it) == 3 && u16(it + 2) == 1 }
                ?.let { cmap + buffer.getInt(it + 4) }
                ?: error("没有 (3, 1) cmap 子表")
            check(u16(sub) == 4) { "只认格式 4" }
            val segments = u16(sub + 6) / 2
            val ends = sub + 14
            val starts = ends + segments * 2 + 2
            val deltas = starts + segments * 2
            val ranges = deltas + segments * 2
            for (i in 0 until segments) {
                val end = u16(ends + i * 2)
                if (codePoint > end) continue
                val start = u16(starts + i * 2)
                if (codePoint < start) return false
                val delta = buffer.getShort(deltas + i * 2).toInt()
                val rangeAt = ranges + i * 2
                val rangeOffset = u16(rangeAt)
                val glyph = if (rangeOffset == 0) {
                    (codePoint + delta) and 0xFFFF
                } else {
                    val raw = u16(rangeAt + rangeOffset + (codePoint - start) * 2)
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                return glyph != 0
            }
            return false
        }
    }

    /** 单测的工作目录可能是 app/ 也可能是仓库根，逐级向上找。 */
    private fun locate(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val hit = listOf(File(dir, relative), File(dir, "app/$relative")).firstOrNull { it.isFile }
            if (hit != null) return hit
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $relative")
    }
}
