package com.mytv.mymusic.ui.player.stage

import androidx.compose.ui.geometry.Rect
import com.mytv.mymusic.ui.player.stage.modes.VinylDiscVisualizer
import com.mytv.mymusic.ui.player.stage.modes.VinylHold
import com.mytv.mymusic.ui.player.stage.modes.vinylAngle
import com.mytv.mymusic.ui.player.stage.modes.vinylFitRows
import com.mytv.mymusic.ui.player.stage.modes.vinylGeometry
import com.mytv.mymusic.ui.player.stage.modes.vinylMetaTexts
import com.mytv.mymusic.ui.player.stage.modes.vinylRowAlpha
import com.mytv.mymusic.ui.player.stage.modes.vinylRows
import com.mytv.mymusic.ui.player.stage.modes.vinylUsable
import com.mytv.mymusic.ui.player.stage.modes.vinylWordProgress
import com.mytv.mymusic.ui.player.stage.modes.vinylWordScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VinylDiscTest {

    // ---- 布局 ----

    private val landscape = Rect(0f, 120f, 2400f, 960f)
    private val portrait = Rect(0f, 200f, 1080f, 2400f)

    @Test
    fun wideRightDiscSitsBesideTheLyrics() {
        val g = vinylGeometry(landscape, wide = true, discLeft = false, discSize = 0.62f, showArm = true, minDisc = 176f)
        val w = landscape.width
        val d = landscape.height * 0.62f
        assertEquals(d, g.disc.width, 0.01f)
        assertEquals(g.disc.width, g.disc.height, 0.01f)
        assertEquals(landscape.right - w * 0.15f, g.disc.right, 0.01f)
        assertEquals(landscape.left + w * 0.15f, g.text.left, 0.01f)
        assertEquals(g.disc.left - w * 0.03f, g.text.right, 0.01f)
        assertEquals(landscape.center.y, g.disc.center.y, 0.01f)
        assertTrue(g.header)
        assertInside(landscape, g.disc, armReach = 0.125f)
        assertInside(landscape, g.text)
    }

    @Test
    fun wideLeftDiscLeavesRoomForTheLiftedArm() {
        val g = vinylGeometry(landscape, wide = true, discLeft = true, discSize = 0.62f, showArm = true, minDisc = 176f)
        assertEquals(landscape.left + landscape.width * 0.15f, g.disc.left, 0.01f)
        // 间隔 = 3% 宽 + 唱臂抬起伸出的 12% 直径
        assertEquals(g.disc.right + landscape.width * 0.03f + g.disc.width * 0.12f, g.text.left, 0.01f)
        assertEquals(landscape.right - landscape.width * 0.15f, g.text.right, 0.01f)
        val noArm = vinylGeometry(landscape, wide = true, discLeft = true, discSize = 0.62f, showArm = false, minDisc = 176f)
        assertEquals(noArm.disc.right + landscape.width * 0.03f, noArm.text.left, 0.01f)
    }

    @Test
    fun fullSizeDiscStillKeepsTheArmInsideTheSafeArea() {
        val g = vinylGeometry(landscape, wide = true, discLeft = false, discSize = 1f, showArm = true, minDisc = 176f)
        assertEquals(landscape.height / 1.125f, g.disc.width, 0.01f)
        assertInside(landscape, g.disc, armReach = 0.125f)
        // 窄横屏：直径受宽度 42% 限制
        val narrow = Rect(0f, 0f, 900f, 800f)
        val n = vinylGeometry(narrow, wide = true, discLeft = false, discSize = 1f, showArm = true, minDisc = 176f)
        assertEquals(900f * 0.42f, n.disc.width, 0.01f)
    }

    @Test
    fun portraitStacksDiscOverLyricsWithoutTitle() {
        val g = vinylGeometry(portrait, wide = false, discLeft = true, discSize = 0.62f, showArm = true, minDisc = 176f)
        assertFalse(g.header)
        assertEquals(portrait.width * 0.62f, g.disc.width, 0.01f)
        assertEquals(portrait.center.x, g.disc.center.x, 0.01f)
        assertTrue(g.disc.top - 0.125f * g.disc.width >= portrait.top)
        assertTrue(g.text.top > g.disc.bottom)
        assertEquals(portrait.bottom, g.text.bottom, 0.01f)
        assertEquals(portrait.left + portrait.width * 0.08f, g.text.left, 0.01f)
        assertInside(portrait, g.disc, armReach = 0.125f)
        // 唱片位置在竖屏不起作用
        val right = vinylGeometry(portrait, wide = false, discLeft = false, discSize = 0.62f, showArm = true, minDisc = 176f)
        assertEquals(g.disc, right.disc)
        // 最大也只到宽 80%、高 46%
        val big = vinylGeometry(portrait, wide = false, discLeft = false, discSize = 1f, showArm = true, minDisc = 176f)
        assertEquals(portrait.width * 0.8f, big.disc.width, 0.01f)
        val short = Rect(0f, 0f, 1080f, 900f)
        assertEquals(900f * 0.46f, vinylGeometry(short, wide = false, discLeft = false, discSize = 1f, showArm = true, minDisc = 176f).disc.width, 0.01f)
    }

    @Test
    fun tinySafeAreaWinsOverMinimumDiscSize() {
        val tiny = Rect(0f, 0f, 800f, 150f)
        val g = vinylGeometry(tiny, wide = true, discLeft = false, discSize = 0.3f, showArm = true, minDisc = 176f)
        assertEquals(150f / 1.125f, g.disc.width, 0.01f)
        assertInside(tiny, g.disc, armReach = 0.125f)
        val small = vinylGeometry(landscape, wide = true, discLeft = false, discSize = 0.3f, showArm = true, minDisc = 300f)
        assertEquals(300f, small.disc.width, 0.01f)
    }

    // ---- 旋转、填色 ----

    @Test
    fun discTurnsOncePerSpinPeriod() {
        assertEquals(0f, vinylAngle(0f, 20f), 1e-4f)
        assertEquals(90f, vinylAngle(5f, 20f), 1e-3f)
        assertEquals(180f, vinylAngle(30f, 20f), 1e-3f)
        assertEquals(0f, vinylAngle(40f, 20f), 1e-3f)
        assertEquals(36f, vinylAngle(0.6f, 6f), 1e-3f)
        for (s in 0..500) {
            val a = vinylAngle(s * 0.73f, 20f)
            assertTrue(a >= 0f && a < 360f)
        }
        assertEquals(0f, vinylAngle(12f, 0f), 0f)
    }

    @Test
    fun wordFillSweepsAcrossTheWord() {
        assertEquals(0f, vinylWordProgress(900, 1000, 1400), 0f)
        assertEquals(0.5f, vinylWordProgress(1200, 1000, 1400), 1e-6f)
        assertEquals(1f, vinylWordProgress(2000, 1000, 1400), 0f)
        // 起止相同：过了算唱完
        assertEquals(0f, vinylWordProgress(999, 1000, 1000), 0f)
        assertEquals(1f, vinylWordProgress(1000, 1000, 1000), 0f)
    }

    @Test
    fun singingWordPopsAndSettlesBack() {
        assertEquals(1f, vinylWordScale(900, 1000, 2000), 0f)
        assertEquals(1f, vinylWordScale(1000, 1000, 2000), 0f)
        assertTrue(vinylWordScale(1090, 1000, 2000) in 1.001f..1.059f)
        assertEquals(1.06f, vinylWordScale(1500, 1000, 2000), 1e-5f)
        assertTrue(vinylWordScale(2090, 1000, 2000) in 1.001f..1.059f)
        assertEquals(1f, vinylWordScale(2180, 1000, 2000), 1e-5f)
        // 唱得比过渡还快的词放不到最大
        assertTrue(vinylWordScale(1060, 1000, 1060) < 1.05f)
        assertEquals(1f, vinylWordScale(1500, 1000, 1000), 0f)
    }

    // ---- 显示哪几句 ----

    @Test
    fun windowShowsTwoBeforeAndFourAfter() {
        val usable = BooleanArray(12) { true }
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9), vinylRows(usable, 5, showNext = true))
        assertEquals(listOf(3, 4, 5), vinylRows(usable, 5, showNext = false))
        assertEquals(listOf(0, 1, 2, 3, 4), vinylRows(usable, 0, showNext = true))
        // 第一句之前：只有后面几句
        assertEquals(listOf(0, 1, 2, 3), vinylRows(usable, -1, showNext = true))
        assertEquals(emptyList<Int>(), vinylRows(usable, -1, showNext = false))
        // 跳过不能显示的
        usable[4] = false
        usable[7] = false
        assertEquals(listOf(2, 3, 5, 6, 8, 9, 10), vinylRows(usable, 5, showNext = true))
    }

    @Test
    fun metadataLinesAreSkippedButBareTitlesStay() {
        val meta = vinylMetaTexts("晴天", "周杰伦")
        assertTrue("晴天 - 周杰伦" in meta)
        assertTrue("周杰伦 - 晴天" in meta)
        assertTrue("晴天·周杰伦" in meta)
        assertTrue(vinylMetaTexts("", "周杰伦").isEmpty())
        val lines = listOf(line(0, "晴天 - 周杰伦"), line(1, "晴天"), line(2, "  "), line(3, "故事的小黄花"))
        assertEquals(listOf(false, true, false, true), vinylUsable(lines, "晴天", "周杰伦").toList())
    }

    @Test
    fun holdKeepsTheLastLineThroughGaps() {
        val usable = booleanArrayOf(true, false, true, true)
        val hold = VinylHold()
        assertEquals(-1, hold.resolve(usable, -1))
        assertFalse(hold.moved)
        assertEquals(0, hold.resolve(usable, 0))
        assertTrue(hold.moved)
        assertEquals(0, hold.resolve(usable, -1))
        assertEquals(0, hold.resolve(usable, 1))
        assertEquals(2, hold.resolve(usable, 2))
        assertEquals(2, hold.resolve(usable, -1))
        val fresh = VinylHold()
        assertEquals(3, fresh.resolve(usable, 3))
        assertEquals(3, fresh.resolve(usable, 3))
        assertFalse(fresh.moved)
    }

    @Test
    fun rowsFadeWithDistance() {
        assertEquals(1f, vinylRowAlpha(0), 0f)
        assertEquals(0.62f, vinylRowAlpha(1), 1e-6f)
        assertEquals(0.47f, vinylRowAlpha(2), 1e-6f)
        assertEquals(0.17f, vinylRowAlpha(4), 1e-6f)
        assertEquals(0.16f, vinylRowAlpha(5), 1e-6f)
        assertEquals(0.16f, vinylRowAlpha(9), 1e-6f)
    }

    @Test
    fun fittingDropsTheFarthestRowsFirst() {
        // 行：前 2、当前、后 4，每行 100 高、间隔 10
        val heights = FloatArray(7) { 100f }
        val all = vinylFitRows(heights, 2, header = 80f, gap = 10f, available = 2000f)
        assertTrue(all.keep.all { it })
        assertTrue(all.header)

        // 放得下 5 行加歌名：先省后 4、后 3（离得最远）
        val five = vinylFitRows(heights, 2, header = 80f, gap = 10f, available = 80f + 5 * 100f + 4 * 10f)
        assertEquals(listOf(true, true, true, true, true, false, false), five.keep.toList())

        // 一样远时先省后面的：剩 3 行时是前 1、当前、后 1
        val three = vinylFitRows(heights, 2, header = 0f, gap = 10f, available = 320f)
        assertEquals(listOf(false, true, true, true, false, false, false), three.keep.toList())
        assertFalse(three.header)

        // 只剩当前句还放不下：再省歌名行，当前句总是留着
        val squeezed = vinylFitRows(heights, 2, header = 80f, gap = 10f, available = 120f)
        assertEquals(listOf(false, false, true, false, false, false, false), squeezed.keep.toList())
        assertFalse(squeezed.header)
        val none = vinylFitRows(heights, 2, header = 80f, gap = 10f, available = 10f)
        assertTrue(none.keep[2])

        // 没有当前句（第一句之前）：从最后面省起
        val intro = vinylFitRows(FloatArray(4) { 100f }, -1, header = 0f, gap = 0f, available = 250f)
        assertEquals(listOf(true, true, false, false), intro.keep.toList())
    }

    @Test
    fun declarationMatchesTheBrief() {
        assertEquals("vinyl", VinylDiscVisualizer.id)
        assertEquals(50, VinylDiscVisualizer.order)
        assertTrue(VinylDiscVisualizer.hostBackground)
        assertFalse(VinylDiscVisualizer.hostSubtitles)
        assertTrue(VinylDiscVisualizer.drawsWithoutLyrics)
        assertEquals(3, VinylDiscVisualizer.hiddenSettings)
        assertEquals(
            listOf("discSide", "discSize", "spinSeconds", "halo", "showArm", "sheen", "showNext", "textScale"),
            VinylDiscVisualizer.settings.map { it.key },
        )
        val values = SettingValues(VinylDiscVisualizer.settings, emptyMap())
        assertEquals(0.62f, values.number("discSize"), 1e-6f)
        assertEquals(20f, values.number("spinSeconds"), 1e-6f)
        assertEquals(1.25f, values.number("textScale"), 1e-6f)
        assertEquals("right", values.choice("discSide"))
        assertEquals("cover", values.choice("halo"))
        val size = VinylDiscVisualizer.settings.first { it.key == "discSize" } as LyricSetting.Number
        assertEquals("62%", size.format(0.62f))
        val spin = VinylDiscVisualizer.settings.first { it.key == "spinSeconds" } as LyricSetting.Number
        assertEquals("20 秒", spin.format(20f))
    }

    private fun assertInside(safe: Rect, rect: Rect, armReach: Float = 0f) {
        val eps = 0.01f
        assertTrue("$rect left of $safe", rect.left >= safe.left - eps)
        assertTrue("$rect right of $safe", rect.right <= safe.right + eps)
        assertTrue("$rect above $safe", rect.top - armReach * rect.width >= safe.top - eps)
        assertTrue("$rect below $safe", rect.bottom <= safe.bottom + eps)
    }

    private fun line(index: Int, text: String) =
        StageLine(index, index * 1000L, index * 1000L + 900L, text, emptyList(), "", "")
}
