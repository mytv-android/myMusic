package com.mytv.mymusic.ui.playlists

import com.mytv.mymusic.data.PlaylistCategory

/** 分类分组 chip：固定“全部”在首位，其余去空、去重并保持服务端顺序。 */
internal fun categoryGroups(categories: List<PlaylistCategory>): List<String> =
    listOf("全部") + categories.map { it.group }.filter { it.isNotBlank() && it != "全部" }.distinct()

/** 当前分组下可见的分类；“全部”包含没有分组的分类。 */
internal fun visibleCategories(categories: List<PlaylistCategory>, group: String): List<PlaylistCategory> =
    categories.filter { group == "全部" || it.group == group || it.group.isBlank() }
