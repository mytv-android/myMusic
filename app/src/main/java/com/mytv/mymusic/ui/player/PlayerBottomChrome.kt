package com.mytv.mymusic.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * 播放页底部控件区占的高度（像素）：各播放布局在测量阶段按自己的来源上报，取其中最大的一个，
 * 更新胶囊读它让开，播放页打开时不会压在进度条、按钮组和工具栏上。
 *
 * 按来源分开记：[Panel] 的默认控件和歌词页底部控件、[Tv] 的控制条、[Tabletop] 的下半屏同时存在（切换过程中）时，
 * 最大那个才是真正盖住底边的那块。每个布局离开组合时 [clear] 自己那一份，播放页整体退出时 [PlayerScreen] 再清一次。
 *
 * 只上报会落在胶囊位置上的底部控件：手机和平板的胶囊分别贴底居中、靠右下，平板的控件在左栏和竖向工具栏上，
 * 不会重叠，所以平板播放页不上报。手机和桌面姿态用 safeDrawing 内边距、量的是布局内部的距离，外壳读时再加导航栏 inset；
 * 电视播放页不套内边距，量到的已是整屏坐标。
 */
@Stable
class PlayerBottomChrome {
    private val heights = mutableStateMapOf<String, Int>()

    /** 最高的那块控件区；没有任何布局上报（播放页没开）时为 0。 */
    val bottomPx: Int get() = heights.values.maxOrNull() ?: 0

    fun report(source: String, px: Int) {
        val value = px.coerceAtLeast(0)
        if (heights[source] != value) heights[source] = value
    }

    /** 某个布局离开组合（换成别的形态）时撤掉自己那一份，免得旧高度一直留着。 */
    fun clear(source: String) {
        heights.remove(source)
    }

    /** 播放页整体退出时清空。 */
    fun clearAll() {
        heights.clear()
    }

    companion object {
        const val Panel = "panel"
        const val PanelLyrics = "panelLyrics"
        const val Tv = "tv"
        const val Tabletop = "tabletop"
    }
}

/** 播放页在组合里时由外壳提供；[PlayerScreen] 离开组合时清空自己那几份。 */
val LocalPlayerBottomChrome = compositionLocalOf { PlayerBottomChrome() }
