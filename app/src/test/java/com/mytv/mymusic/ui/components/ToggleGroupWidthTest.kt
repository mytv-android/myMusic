package com.mytv.mymusic.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接式按钮组放不放得下，靠 [toggleGroupMinWidth] 算出的最窄宽度和实测宽度比。
 * 这里按 1dp = 1px、labelLarge 14sp 中文字宽约 14px 算，数字和真机竖屏量到的差不多。
 */
class ToggleGroupWidthTest {

    private val sidePadding = 48
    private val touchTarget = 48
    private val gap = 2

    private fun widthOf(labels: List<String>, icon: Boolean = false): Int = toggleGroupMinWidth(
        textWidths = labels.map { it.length * 14 },
        iconWidths = labels.map { if (icon) 24 else 0 },
        sidePadding = sidePadding,
        minTouchTarget = touchTarget,
        gap = gap,
    )

    @Test
    fun `seven quality tiers do not fit a phone row`() {
        // 自动 / 标准 / 高品质 / 无损 / Hi-Res / 全景声 / 母带
        val labels = listOf("自动", "标准", "高品质", "无损", "Hi-Res", "全景声", "母带")
        assertTrue(widthOf(labels) > 360 - 32)
        assertTrue(widthOf(labels) > 412 - 32)
    }

    @Test
    fun `three short modes still fit`() {
        assertTrue(widthOf(listOf("按音质", "按顺序", "首选平台")) < 360 - 32)
    }

    @Test
    fun `the same tiers fit a wide tablet row`() {
        val labels = listOf("自动", "标准", "高品质", "无损", "Hi-Res", "全景声", "母带")
        assertFalse(widthOf(labels) > 1000)
    }

    @Test
    fun `icon rows add the icon slot to every tier`() {
        val labels = listOf("热门歌曲", "专辑", "简介")
        assertEquals(widthOf(labels) + 3 * 24, widthOf(labels, icon = true))
    }

    @Test
    fun `touch target floors short labels`() {
        // 空标签也有 48dp 触摸目标下限，不能被压得更窄
        assertEquals(touchTarget + gap, toggleGroupMinWidth(listOf(0), listOf(0), 0, touchTarget, gap))
    }
}
