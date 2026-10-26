package com.mytv.mymusic

import com.mytv.mymusic.data.PlaylistCategory
import com.mytv.mymusic.ui.playlists.categoryGroups
import com.mytv.mymusic.ui.playlists.visibleCategories
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistsFiltersTest {
    private fun category(id: String, group: String, name: String = id) =
        PlaylistCategory(id = id, name = name, group = group)

    @Test
    fun groupsKeepOrderAndDropBlankAndDuplicate() {
        val groups = categoryGroups(
            listOf(
                category("1", "流行"),
                category("2", ""),
                category("3", "摇滚"),
                category("4", "流行"),
                category("5", "全部"),
            ),
        )

        assertEquals(listOf("全部", "流行", "摇滚"), groups)
    }

    @Test
    fun allGroupIncludesUngroupedCategories() {
        val categories = listOf(category("1", "流行"), category("2", ""), category("3", "摇滚"))

        assertEquals(listOf("1", "2", "3"), visibleCategories(categories, "全部").map { it.id })
        assertEquals(listOf("1", "2"), visibleCategories(categories, "流行").map { it.id })
    }
}
