package com.mytv.mymusic.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真洛雪（lx-music-mobile v1.9.2，GitHub Release 的 x86_64 包）在模拟器上导出的两份文件：
 * 设置 › 备份与恢复 › 导出列表（playList_v2，含试听列表、我的收藏、自建列表），
 * 以及自建列表 ⋮ › 导出（playListPart_v2）。五个平台各搜了一首「Faded」放进去。
 */
class LxRealExportTest {
    private fun read(name: String): LxListFile {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/lx/$name")).use { it.readBytes() }
        assertTrue(TransferReader.isGzip(bytes))
        assertTrue(TransferReader.looksLikeTransfer(bytes))
        val content = TransferReader.read(bytes)
        return (content as TransferContent.Lx).file
    }

    @Test
    fun allListsExport() {
        val file = read("lx192_all.lxmc")
        assertEquals(LxKind.ListV2, file.kind)
        assertEquals(listOf("default", "love", "userlist_1795024479109"), file.lists.map { it.id })
        assertEquals(listOf(LxFixed.Default, LxFixed.Love, null), file.lists.map { it.fixed })
        assertEquals(listOf(1, 3, 5), file.lists.map { it.songs.size })
        assertEquals(listOf("Default", "Loved", "Running"), file.lists.map { it.name })
    }

    @Test
    fun partExportMapsEveryPlatform() {
        val file = read("lx192_part_Running.lxmc")
        assertEquals(LxKind.PartV2, file.kind)
        val list = file.lists.single()
        assertEquals("Running", list.name)
        assertEquals(null, list.fixed)
        val mapped = list.songs.associate { it.source to it.mapToSong() }
        assertEquals(setOf("kg", "mg", "wy", "tx", "kw"), mapped.keys)

        val kugou = (mapped["kg"] as LxMapped.Ready).song
        assertEquals("kugou", kugou.source)
        assertEquals("02E0E606C6B734100978408ADF46ACB2", kugou.id)
        assertEquals("190027857", kugou.extra["audio_id"])
        assertEquals(286, kugou.duration)

        val migu = mapped["mg"] as LxMapped.Migu
        assertEquals("1002054693", migu.songId)
        assertEquals("6005861NEXC", migu.fallback.extra["copyright_id"])
        assertEquals("ZHU", migu.fallback.artist)

        val netease = (mapped["wy"] as LxMapped.Ready).song
        assertEquals("netease", netease.source)
        assertEquals("3442248762", netease.id)

        val qq = (mapped["tx"] as LxMapped.Ready).song
        assertEquals("qq", qq.source)
        assertEquals("001DTVah2lNPDm", qq.id)
        assertEquals("375054561", qq.extra["song_id"])
        assertEquals("003UXYeB1zT527", qq.extra["album_mid"])

        val kuwo = (mapped["kw"] as LxMapped.Ready).song
        assertEquals("kuwo", kuwo.source)
        assertEquals("665656337", kuwo.id)
        assertEquals("SYNDAMY", kuwo.artist)
    }
}
