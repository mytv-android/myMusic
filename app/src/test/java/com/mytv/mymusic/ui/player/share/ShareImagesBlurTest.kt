package com.mytv.mymusic.ui.player.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShareImagesBlurTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun channel(pixel: Int, c: Int) = (pixel ushr (24 - c * 8)) and 0xFF

    @Test
    fun solidImageStaysTheSame() {
        val color = argb(255, 40, 120, 200)
        val pixels = IntArray(12 * 9) { color }
        assertArrayEquals(pixels, boxBlur(pixels, 12, 9, 3))
    }

    @Test
    fun radiusZeroReturnsACopy() {
        val pixels = IntArray(16) { it * 1000 }
        val out = boxBlur(pixels, 4, 4, 0)
        assertArrayEquals(pixels, out)
        assertFalse(out === pixels)
    }

    @Test
    fun brightPointSpreadsSymmetricallyAndKeepsItsEnergy() {
        val w = 21
        val h = 21
        val black = argb(255, 0, 0, 0)
        val pixels = IntArray(w * h) { black }
        pixels[10 * w + 10] = argb(255, 255, 255, 255)
        val input = pixels.copyOf()
        val out = boxBlur(pixels, w, h, 1)
        assertArrayEquals("输入不改", input, pixels)
        // 上下左右对称
        for (d in 1..3) {
            val center = 10 * w + 10
            val values = listOf(center - d, center + d, center - d * w, center + d * w).map { channel(out[it], 1) }
            assertEquals(1, values.toSet().size)
        }
        assertTrue(channel(out[10 * w + 10], 1) < 255)
        assertTrue(channel(out[10 * w + 12], 1) > 0)
        // 总亮度守恒（逐像素四舍五入，误差在像素数的一半以内，实际很小）
        val total = out.sumOf { channel(it, 1) }
        assertTrue("total $total", abs(total - 255) <= 25)
        // 不透明度不变
        assertTrue(out.all { channel(it, 0) == 255 })
    }

    @Test
    fun alphaIsBlurredToo() {
        val w = 9
        val h = 1
        val pixels = IntArray(w) { if (it == 4) argb(255, 0, 0, 0) else 0 }
        val out = boxBlur(pixels, w, h, 1)
        assertTrue(channel(out[3], 0) in 1..254)
        assertTrue(channel(out[5], 0) in 1..254)
    }

    @Test
    fun tinyImagesDoNotOverflow() {
        assertArrayEquals(intArrayOf(argb(255, 9, 9, 9)), boxBlur(intArrayOf(argb(255, 9, 9, 9)), 1, 1, 5))
        val narrow = IntArray(2 * 7) { argb(255, it * 10, 0, 0) }
        assertEquals(14, boxBlur(narrow, 2, 7, 6).size)
        assertEquals(14, boxBlur(narrow, 7, 2, 6).size)
    }

    @Test
    fun cjkOnlyDetection() {
        assertTrue(isCjkOnly("晴天"))
        assertTrue(isCjkOnly("周杰伦、蔡依林"))
        assertTrue(isCjkOnly("さくら 独唱"))
        assertTrue(isCjkOnly("아이유"))
        assertFalse(isCjkOnly("Porch Light"))
        assertFalse(isCjkOnly("晴天 (Live)"))
        assertFalse(isCjkOnly("  "))
        assertFalse(isCjkOnly(""))
    }
}
