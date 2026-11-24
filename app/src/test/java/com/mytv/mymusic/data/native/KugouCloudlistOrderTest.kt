package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 酷狗云歌单跨页拼齐后的排序。
 *
 * 服务端的 `sort` 是播放列表里的全局位置（0 在最前，加歌接口写的就是 `sort=0`），
 * 但 `get_list_all_file` 翻页回来的数组不按它连续排——直接拿数组顺序显示，
 * 最新加进去的歌就沉在最后（issue #35）。一条位置信息都没有时保持数组原序，不猜方向。
 */
class KugouCloudlistOrderTest {

    @Test
    fun `sorts globally by server position across pages`() {
        // 四页各 300 首，数组顺序是 sort 递减；按 sort 升序后 fileid 1060（sort=0）在最前
        val rows = (0 until 1060).map { index -> Row(fileid = index + 1, sort = 1059 - index) }
        val ordered = rows.orderedByServerPosition { it.sort }
        assertEquals(1060, ordered.size)
        assertEquals(1060, ordered.first().fileid)
        assertEquals(1, ordered.last().fileid)
        assertEquals(rows.map { it.fileid }.reversed(), ordered.map { it.fileid })
    }

    @Test
    fun `position from a later page moves to the front`() {
        // 第一页的 sort 是 300..1，第二页那首是 0：不做全局排序它就会被埋到最后
        val rows = (0 until 300).map { Row(fileid = it + 1, sort = 300 - it) } + Row(301, 0)
        val ordered = rows.orderedByServerPosition { it.sort }
        assertEquals(listOf(301, 300, 299, 298), ordered.take(4).map { it.fileid })
        assertEquals(301, ordered.size)
    }

    @Test
    fun `keeps the api order when no position is returned`() {
        val rows = listOf(Row(1, null), Row(2, null), Row(3, null))
        assertEquals(rows, rows.orderedByServerPosition { it.sort })
    }

    @Test
    fun `missing positions sink and ties keep the original order`() {
        val rows = listOf(Row(1, null), Row(2, 7), Row(3, null), Row(4, 7), Row(5, 0))
        val ordered = rows.orderedByServerPosition { it.sort }
        assertEquals(listOf(5, 2, 4, 1, 3), ordered.map { it.fileid })
    }

    private data class Row(val fileid: Int, val sort: Int?)
}
