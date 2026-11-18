package com.mytv.mymusic.data.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiguCopyrightTest {
    @Test
    fun batchesOfHundredAfterDedupe() {
        val ids = (1..250).map { "id$it" } + listOf("id1", " id2 ", "", "  ")
        val batches = resourceBatches(ids)
        assertEquals(listOf(100, 100, 50), batches.map { it.size })
        assertEquals(250, batches.flatten().toSet().size)
        assertTrue(resourceBatches(emptyList()).isEmpty())
    }

    @Test
    fun resourceInfoIsKeyedBySongId() {
        // 照 resourceinfo.do?resourceType=2 的返回裁出来的结构
        val body = """
            {"code":"000000","info":"成功","resource":[
              {"resourceType":"2","contentId":"600907000009041441","copyrightId":"60084600529","songId":"1121438701","songName":"小幸运",
               "artists":[{"id":"1","name":"田馥甄"}],"album":"我的少女时代","albumId":"1121438700",
               "albumImgs":[{"imgSizeType":"01","img":"http://d.musicapp.migu.cn/a.jpg"}],
               "newRateFormats":[{"resourceType":"2","formatType":"PQ","size":"4062189","fileType":"mp3"},
                                 {"resourceType":"2","formatType":"HQ","size":"10155349","fileType":"mp3"}]},
              {"resourceType":"2","contentId":"600900000000000001","copyrightId":"69999999999","songId":"2","songName":"没格式","newRateFormats":[]},
              {"resourceType":"2","contentId":"600900000000000002","copyrightId":"","songId":"3","songName":"没版权号",
               "newRateFormats":[{"resourceType":"2","formatType":"PQ","size":"1"}]}
            ]}
        """.trimIndent()
        val songs = MiguProvider().parseResourceInfo(body)
        // 没版权号的照样收（key 是 songId），没格式的不收
        assertEquals(setOf("1121438701", "3"), songs.keys)
        val song = songs.getValue("1121438701")
        assertEquals("migu", song.source)
        assertTrue(song.id.startsWith("600907000009041441|2|"))
        assertEquals("600907000009041441", song.extra["content_id"])
        assertEquals("60084600529", song.extra["copyright_id"])
        assertEquals("小幸运", song.name)
        assertEquals("田馥甄", song.artist)
        assertFalse(MiguProvider().parseResourceInfo("not json").isNotEmpty())
    }

    /**
     * 2026-11-19 实抓：resourceinfo.do?resourceType=2，resourceId=「1002054693|6005861NEXC」（洛雪 1.9.2 导出的那首咪咕
     * 「Faded」的 songId 和版权号）。只有 songId 查到了东西，版权号查回来是空的——换取必须按 songId。
     */
    @Test
    fun realResponseResolvesBySongIdOnly() {
        val body = checkNotNull(javaClass.getResourceAsStream("/migu/resourceinfo_faded.json")).use { it.readBytes().toString(Charsets.UTF_8) }
        val songs = MiguProvider().parseResourceInfo(body)
        assertEquals(setOf("1002054693"), songs.keys)
        val song = songs.getValue("1002054693")
        assertTrue(song.id, song.id.startsWith("600929000003454435|"))
        assertEquals("6005861NEXC", song.extra["copyright_id"])
        assertEquals("Faded", song.name)
        assertEquals("ZHU", song.artist)
        assertEquals(223, song.duration)
    }
}
