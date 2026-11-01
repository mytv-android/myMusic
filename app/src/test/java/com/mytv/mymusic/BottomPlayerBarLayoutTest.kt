package com.mytv.mymusic

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.mymusic.ui.adaptive.BottomPlayerBarCompactBelow
import com.mytv.mymusic.ui.adaptive.BottomPlayerBarCompactGap
import com.mytv.mymusic.ui.adaptive.BottomPlayerBarInfoMax
import com.mytv.mymusic.ui.adaptive.BottomPlayerBarInfoMin
import com.mytv.mymusic.ui.adaptive.BottomPlayerBarSeekMin
import com.mytv.mymusic.ui.adaptive.bottomBarInfoWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平板底部播放条不分两段时，歌名块和进度条怎么分宽度。宽度按实际控件算：侧栏 96dp、左右各留 16dp；
 * 常规是块间距 12dp、48 + 56 + 48 的播放键组（组内也隔 12）和两个 48dp 钮（隔 12），
 * 紧凑是块间距 8dp、48 + 48 + 48 和两个 48dp 钮（组内不隔）。
 */
class BottomPlayerBarLayoutTest {
    private val rail = 96.dp
    private val side = 16.dp
    private val gap = 12.dp

    private fun content(window: Dp) = window - rail - side * 2
    private fun compact(window: Dp) = content(window) < BottomPlayerBarCompactBelow

    /** 歌名和进度条一起分的宽度。 */
    private fun rest(window: Dp): Dp {
        if (compact(window)) return content(window) - 48.dp * 3 - 48.dp * 2 - BottomPlayerBarCompactGap * 3
        return content(window) - (48.dp + 56.dp + 48.dp + gap * 2) - (48.dp * 2 + gap) - gap * 3
    }

    /** 歌名块里留给文字的宽：内边距 4 × 2、封面、封面和文字的间距。 */
    private fun textWidth(window: Dp, info: Dp) = info - 8.dp - if (compact(window)) 48.dp + 8.dp else 56.dp + 12.dp

    /** “如果乌鸦说情话”：7 个 titleMedium 汉字，16sp 加 0.15sp 字距。 */
    private val sevenChars = 16.15.dp * 7

    @Test
    fun portraitTabletShowsSevenCharactersAndKeepsTheSeekBar() {
        // Pixel Fold 竖着展平：701dp 宽，内容区约 573dp，用紧凑尺寸（改之前歌名只剩一个字）
        val window = 701.dp
        assertTrue(compact(window))
        val rest = rest(window)
        val info = bottomBarInfoWidth(rest)
        assertEquals(BottomPlayerBarInfoMin, info)
        assertTrue("文字只有 ${textWidth(window, info)}", textWidth(window, info) >= sevenChars)
        assertTrue("进度条只剩 ${rest - info}", rest - info >= BottomPlayerBarSeekMin)
    }

    @Test
    fun landscapeFoldSplitsEvenlyAndFitsTheTitle() {
        // 横着展平 841dp：不紧凑，歌名拿一半
        val window = 841.dp
        assertFalse(compact(window))
        val rest = rest(window)
        val info = bottomBarInfoWidth(rest)
        assertEquals(rest * 0.5f, info)
        assertTrue("文字只有 ${textWidth(window, info)}", textWidth(window, info) >= sevenChars)
        assertTrue(rest - info >= BottomPlayerBarSeekMin)
    }

    @Test
    fun wideTabletCapsTheTitleBlock() {
        // 1199dp：歌名块封顶 280，其余都给进度条（和改之前一样）
        val rest = rest(1199.dp)
        assertEquals(BottomPlayerBarInfoMax, bottomBarInfoWidth(rest))
    }

    @Test
    fun veryNarrowBarsShrinkBothSidesEvenly() {
        // 补不到 184 又要给进度条留 120 时：不再补，两边各一半
        val rest = 196.dp
        assertEquals(98.dp, bottomBarInfoWidth(rest))
        // 补一点就够：歌名块补到让进度条正好 120
        assertEquals(130.dp, bottomBarInfoWidth(250.dp))
        assertEquals(0.dp, bottomBarInfoWidth(0.dp))
    }
}
