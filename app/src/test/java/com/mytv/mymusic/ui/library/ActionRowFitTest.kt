package com.mytv.mymusic.ui.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 歌单详情操作行按宽度排；宽度是模拟器上量出来的（播放全部约 163dp，“添加”写全约 108dp）。 */
class ActionRowFitTest {
    private val play = 163.dp
    private val addLabel = 108.dp

    @Test
    fun phoneFitsEverythingWithLabel() {
        // 手机 411dp 减两边 16dp；编辑和更多在顶栏
        val fit = fitActionRow(379.dp, play, addLabel, hasMenu = false, add = true, multi = true, poster = false, edit = false)
        assertEquals(ActionRowFit(add = true, addLabel = true, multi = true, poster = false, edit = false), fit)
    }

    @Test
    fun tvLeftColumnKeepsMultiSelectAndShrinksAdd() {
        // 电视双栏左栏 296dp：之前“添加”写全，多选只露半截
        val fit = fitActionRow(296.dp, play, addLabel, hasMenu = false, add = true, multi = true, poster = false, edit = false)
        assertEquals(ActionRowFit(add = true, addLabel = false, multi = true, poster = false, edit = false), fit)
    }

    @Test
    fun narrowEmbeddedPaneMovesTheRestIntoMore() {
        // 折叠屏展开后“我的”右栏只有 276dp：之前编辑和“更多”被挤没，重命名、导出、删除进不去
        val fit = fitActionRow(276.dp, play, addLabel, hasMenu = true, add = true, multi = true, poster = true, edit = true)
        assertEquals(ActionRowFit(add = true, addLabel = false, multi = false, poster = false, edit = false), fit)
    }

    @Test
    fun wideEmbeddedPaneShowsEverything() {
        val fit = fitActionRow(560.dp, play, addLabel, hasMenu = true, add = true, multi = true, poster = true, edit = true)
        assertEquals(ActionRowFit(add = true, addLabel = true, multi = true, poster = true, edit = true), fit)
    }

    @Test
    fun tooNarrowEvenForAddPutsAddInMore() {
        // 字放大后播放全部更宽
        val fit = fitActionRow(276.dp, 200.dp, addLabel, hasMenu = true, add = true, multi = true, poster = true, edit = true)
        assertEquals(ActionRowFit(add = false, addLabel = false, multi = false, poster = false, edit = false), fit)
    }
}
