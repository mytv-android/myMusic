package com.mytv.mymusic

import com.mytv.mymusic.ui.player.phoneStageSlots
import com.mytv.mymusic.ui.player.segmentWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机播放页的竖向分配和连接按钮组的段宽（都按 1dp = 1px 计算）。 */
class PhoneStageLayoutTest {
    private val ideal = intArrayOf(18, 22, 20, 14)
    private val min = intArrayOf(8, 14, 12, 10)

    private fun slots(bottom: Int, meta: Int = 62) =
        phoneStageSlots(top = 52, bottom = bottom, meta = meta, preview = 56, coverMax = 280, coverMin = 200, ideal = ideal, min = min)

    @Test
    fun referencePhoneKeepsTheFullCoverWithIdealGaps() {
        // 360×800、状态栏和手势条各 24、控件区 224：中段正好放下 280 的封面
        val s = slots(bottom = 752 - 8 - 224, meta = 62)
        assertTrue("cover=${s.cover}", s.cover in 270..280)
    }

    @Test
    fun tallScreensCenterTheMiddleGroupInsteadOfGrowingPastTheCap() {
        val s = slots(bottom = 900)
        assertEquals(280, s.cover)
        val above = s.coverTop - 52
        val below = 900 - (s.previewTop + 56)
        // 多出来的空间平分到封面上方和控件上方（各自加上理想间距）
        assertTrue("above=$above below=$below", kotlin.math.abs((above - ideal[0]) - (below - ideal[3])) <= 1)
    }

    @Test
    fun shortScreensCompressGapsBeforeShrinkingTheCover() {
        val roomForIdeal = 52 + 62 + 56 + 280 + ideal.sum()
        val s = slots(bottom = roomForIdeal - 20)
        assertEquals(280, s.cover)
        assertTrue(s.coverTop - 52 in min[0]..ideal[0])
    }

    @Test
    fun withoutSyncedLyricsThePreviewSpaceIsGivenBack() {
        fun at(bottom: Int, preview: Int) =
            phoneStageSlots(top = 52, bottom = bottom, meta = 62, preview = preview, coverMax = 280, coverMin = 200, ideal = ideal, min = min)
        // 空间有富余：封面不变，整组少了 56 的预览后重新居中，往下挪一半
        val tallWith = at(900, 56)
        val tallWithout = at(900, 0)
        assertEquals(tallWith.cover, tallWithout.cover)
        assertEquals(28, tallWithout.coverTop - tallWith.coverTop)
        assertEquals(tallWithout.metaTop + 62 + ideal[2], tallWithout.previewTop)
        // 空间紧时让出来的高度先还给间距、再还给封面；收放过渡的中间值也单调
        val tight = 52 + 62 + 56 + 200 + min.sum()
        var previous = 0
        for (preview in 56 downTo 0 step 8) {
            val cover = at(tight, preview).cover
            assertTrue("preview=$preview cover=$cover", cover >= previous)
            previous = cover
        }
        assertEquals(256, at(tight, 0).cover)
    }

    @Test
    fun theCoverShrinksButNotBelowItsMinimum() {
        val twoLineTitle = slots(bottom = 52 + 99 + 56 + 280 + min.sum() - 30, meta = 99)
        assertEquals(250, twoLineTitle.cover)
        assertEquals(200, slots(bottom = 300).cover)
    }

    @Test
    fun groupWidthNeverChangesWhilePlayingOrPressed() {
        val total = 312f
        listOf(4f, 6f, 8f).forEach { gap ->
            listOf(
                Triple(0f, 0f, 0f), Triple(1f, 0f, 0f), Triple(0f, 1f, 0f), Triple(0f, 0f, 1f), Triple(0.3f, 0.7f, 0f),
            ).forEach { (lead, middle, trail) ->
                val w = segmentWidths(total, play = 144f, gap = gap, grow = 20f, lead = lead, middle = middle, trail = trail)
                assertEquals(total, w.sum() + gap * 2, 1e-3f)
            }
        }
        // 暂停 80 / 144 / 80；播放中间距 8，两侧各让 4
        assertEquals(listOf(80f, 144f, 80f), segmentWidths(total, 144f, 4f, 20f, 0f, 0f, 0f).toList())
        assertEquals(listOf(76f, 144f, 76f), segmentWidths(total, 144f, 8f, 20f, 0f, 0f, 0f).toList())
        // 按下中段：中段 +20，两侧各 -10；按下上一首：它 +20，中段 -20
        assertEquals(listOf(70f, 164f, 70f), segmentWidths(total, 144f, 4f, 20f, 0f, 1f, 0f).toList())
        assertEquals(listOf(100f, 124f, 80f), segmentWidths(total, 144f, 4f, 20f, 1f, 0f, 0f).toList())
    }
}
