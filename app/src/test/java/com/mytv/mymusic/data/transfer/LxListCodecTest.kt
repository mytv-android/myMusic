package com.mytv.mymusic.data.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LxListCodecTest {
    private fun resource(name: String): JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/lx/$name")).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject

    private fun parse(text: String): LxListFile = LxListCodec.parse(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun partV2SingleList() {
        val file = LxListCodec.parse(resource("part_v2.json"))
        assertEquals(LxKind.PartV2, file.kind)
        val list = file.lists.single()
        assertEquals("跑步", list.name)
        assertEquals("userlist_1700000000000", list.id)
        assertNull(list.fixed)
        assertEquals("wy", list.source)
        assertEquals("8888", list.sourceListId)
        // 10 首：id 空一首、同 id 重复一首按 filterMusicList 丢掉
        assertEquals(8, list.songs.size)
        assertEquals(2, list.dropped)
    }

    @Test
    fun listV2FixedListsById() {
        val file = LxListCodec.parse(resource("list_v2.json"))
        assertEquals(listOf(LxFixed.Default, LxFixed.Love, LxFixed.Temp, null), file.lists.map { it.fixed })
        // 桌面版名字是 i18n 键，换成固定名
        assertEquals("试听列表", file.lists[0].name)
        assertEquals("我的收藏", file.lists[1].name)
        // singer 为 null 补空串
        assertEquals("", file.lists[1].songs[0].singer)
        assertEquals("跑步", file.lists[3].name)
    }

    @Test
    fun allDataV2OnlyTakesPlayList() {
        val file = parse(
            """{"type":"allData_v2","setting":{"version":"2.0"},"playList":[{"id":"love","name":"我的收藏","list":[{"id":"wy_1","name":"a","singer":"b","source":"wy","interval":"01:00","meta":{"songId":1}}]}]}""",
        )
        assertEquals(LxKind.AllV2, file.kind)
        assertEquals(LxFixed.Love, file.lists.single().fixed)
        assertEquals(1, file.lists.single().songs.size)
    }

    @Test
    fun v1Conversion() {
        val file = LxListCodec.parse(resource("part_v1.json"))
        assertEquals(LxKind.PartV1, file.kind)
        val (kg, tx, mg, local) = file.lists.single().songs
        assertEquals("32218394_9E0A6B6B1A7DEF8A6A1D0E3C6F7B2E21", kg.id)
        assertEquals("9E0A6B6B1A7DEF8A6A1D0E3C6F7B2E21", kg.meta["hash"])
        assertEquals("107019867", kg.meta["albumAudioId"])
        assertEquals("32218394", kg.meta["songId"])
        assertEquals("http://imge.kugou.com/a.jpg", kg.meta["picUrl"])
        assertEquals("960399", kg.meta["albumId"])
        assertEquals("tx_0039MnYb0qxYhV", tx.id)
        assertEquals("97773", tx.meta["id"])
        assertEquals("000MkMni19ClKG", tx.meta["albumMid"])
        assertEquals("60084600529", mg.meta["copyrightId"])
        // local：filePath 缺省用 songmid，ext 取后缀
        assertEquals("/sdcard/Music/夜曲.flac", local.meta["filePath"])
        assertEquals("flac", local.meta["ext"])
        assertEquals(326, kg.intervalSec)
    }

    @Test
    fun v1MissingTypesDoesNotThrow() {
        val file = parse(
            """{"type":"playListPart","data":{"id":"x","name":"x","list":[{"songmid":"1","name":"a","singer":"b","source":"wy","interval":"01:00"}]}}""",
        )
        assertEquals(1, file.lists.single().songs.size)
        assertEquals("wy_1", file.lists.single().songs.single().id)
    }

    @Test
    fun allDataV1AndDefaultList() {
        val withDefault = parse(
            """{"type":"allData","defaultList":{"list":[{"songmid":"1","name":"a","singer":"b","source":"wy"}]},"playList":[{"id":"u","name":"u","list":[]}]}""",
        )
        assertEquals(LxFixed.Default, withDefault.lists.single().fixed)
        assertEquals(1, withDefault.lists.single().songs.size)
        val withoutDefault = parse("""{"type":"allData","playList":[{"id":"u","name":"别的","list":[]},{"id":"love","name":"","list":[]}]}""")
        assertEquals(listOf("别的", "我的收藏"), withoutDefault.lists.map { it.name })
        val defautl = parse("""{"type":"defautlList","data":{"list":[{"songmid":"9","name":"c","singer":"d","source":"kw"}]}}""")
        assertEquals("试听列表", defautl.lists.single().name)
        assertEquals("kw_9", defautl.lists.single().songs.single().id)
    }

    @Test
    fun filterDropsBlankAndDuplicates() {
        val file = parse(
            """{"type":"playListPart_v2","data":{"id":"x","name":"x","list":[
              {"id":"","name":"a","source":"wy","meta":{}},
              {"id":"wy_1","name":"","source":"wy","meta":{}},
              {"id":"wy_2","name":"b","source":"wy","meta":{}},
              {"id":"wy_2","name":"b again","source":"wy","meta":{}}
            ]}}""",
        )
        val list = file.lists.single()
        assertEquals(listOf("b"), list.songs.map { it.name })
        assertEquals(3, list.dropped)
    }

    @Test
    fun numericSongIdHasNoDecimal() {
        val file = parse(
            """{"type":"playListPart_v2","data":{"id":"x","name":"x","list":[{"id":"wy_1","name":"a","source":"wy","meta":{"songId":1901371647,"albumId":12.0}}]}}""",
        )
        val meta = file.lists.single().songs.single().meta
        assertEquals("1901371647", meta["songId"])
        assertEquals("12", meta["albumId"])
    }

    @Test
    fun intervals() {
        assertEquals(252, LxListCodec.parseInterval("04:12"))
        assertEquals(3723, LxListCodec.parseInterval("1:02:03"))
        assertEquals(0, LxListCodec.parseInterval(null))
        assertEquals(0, LxListCodec.parseInterval("abc"))
        assertEquals(0, LxListCodec.parseInterval("12"))
    }

    @Test
    fun longNamesAreClipped() {
        val name = "长".repeat(100)
        val file = parse("""{"type":"playListPart_v2","data":{"id":"x","name":"$name","list":[]}}""")
        assertEquals(80, file.lists.single().name.length)
    }

    @Test
    fun brokenSongOnlyDropsThatSong() {
        val file = parse(
            """{"type":"playListPart_v2","data":{"id":"x","name":"x","list":[
              {"id":"wy_1","name":"a","source":"wy","meta":"oops"},
              5,
              {"id":"wy_2","name":"b","source":"wy","meta":{"songId":2}}
            ]}}""",
        )
        assertEquals(listOf("wy_2"), file.lists.single().songs.map { it.id })
        assertEquals(2, file.lists.single().dropped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun brokenStructureThrows() {
        parse("""{"type":"playList_v2","data":"nope"}""")
    }

    @Test
    fun fixedOf() {
        assertEquals(LxFixed.Temp, LxListCodec.fixedOf("temp"))
        assertNull(LxListCodec.fixedOf("userlist_1"))
        assertTrue(LxKind.PartV1.legacy)
    }
}
